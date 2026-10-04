<?php
/** Plugin Name: NenoTV Release Notifications
 * Version: 0.1.0
 * Only publishes notifications; never commits or rolls out Google Play releases.
 */
if (!defined('ABSPATH')) exit;
final class NenoTV_Release_Notifications {
    const PACKAGE = 'com.nenotv.player';
    const OPTION = 'nenotv_release_notifications';
    const AUTH = 'nenotv_release_oauth_credentials';
    const CRON = 'nenotv_release_notifications_poll';
    const SCOPE = 'https://www.googleapis.com/auth/androidpublisher';
    const CALLBACK = 'nenotv_ft_google_oauth_callback';
    static function init(): void {
        add_action('rest_api_init', [self::class, 'routes']);
        add_action(self::CRON, [self::class, 'poll']);
        add_action('init', function () { if (!wp_next_scheduled(self::CRON)) wp_schedule_event(time()+300,'hourly',self::CRON); });
        add_action('admin_menu', function () { add_submenu_page('woocommerce','NenoTV updateberichten','Updateberichten','manage_options','nenotv-update-messages',[self::class,'page']); });
        add_action('admin_post_nenotv_release_connect', [self::class, 'connect']);
        add_action('admin_post_' . self::CALLBACK, [self::class, 'callback'], 1);
    }
    static function routes(): void {
        $permission = function () { return current_user_can('manage_options'); };
        register_rest_route('nenotv/v1','/release-notifications/status',['methods'=>'GET','permission_callback'=>$permission,'callback'=>[self::class,'status']]);
        register_rest_route('nenotv/v1','/release-notifications/config',['methods'=>'POST','permission_callback'=>$permission,'callback'=>[self::class,'configure']]);
        register_rest_route('nenotv/v1','/release-notifications/available',['methods'=>'POST','permission_callback'=>$permission,'callback'=>[self::class,'confirmed']]);
        register_rest_route('nenotv/v1','/release-notifications/poll',['methods'=>'POST','permission_callback'=>$permission,'callback'=>function () { self::poll(); return self::status(); }]);
    }
    static function config(): array { $x=get_option(self::OPTION,[]); return is_array($x)?$x:[]; }
    static function status(): array {
        $c=self::config(); $a=get_option(self::AUTH,[]);
        return ['ok'=>true,'package'=>self::PACKAGE,'automatic_play_detection'=>!empty($a['refresh_enc']) && !empty($a['verified']),
            'track'=>$c['track']??'internal','recipient_count'=>count($c['recipients']??[]),
            'latest'=>$c['latest']??null,'last_poll'=>$c['last_poll']??null,'last_error'=>$c['last_error']??'',
            'delivery'=>$c['delivery']??[]];
    }
    static function configure(WP_REST_Request $r) {
        $emails=$r->get_param('recipients'); $track=$r->get_param('track'); $url=$r->get_param('play_url');
        if (!is_array($emails) || count($emails)>100 || !in_array($track,['internal'],true)
            || !preg_match('~^https://play\.google\.com/apps/internaltest/[0-9]+$~D',(string)$url)) return new WP_Error('invalid_config','Ongeldige testgroep.', ['status'=>400]);
        $clean=[];
        foreach($emails as $email) {
            if(!is_string($email) || !is_email($email) || preg_match('/[\r\n,]/',$email)) return new WP_Error('invalid_email','Ongeldig adres.', ['status'=>400]);
            $clean[strtolower(trim($email))]=true;
        }
        $c=self::config(); $c['track']=$track; $c['play_url']=$url; $c['recipients']=array_keys($clean);
        update_option(self::OPTION,$c,false); return self::status();
    }
    static function confirmed(WP_REST_Request $r) {
        if ($r->get_param('package')!==self::PACKAGE || $r->get_param('track')!=='internal'
            || $r->get_param('status')!=='completed' || $r->get_param('console_verified')!==true) return new WP_Error('unconfirmed_release','Bevestig eerst de beschikbare Play-release.', ['status'=>400]);
        $code=filter_var($r->get_param('version_code'), FILTER_VALIDATE_INT, ['options'=>['min_range'=>1]]);
        $name=(string)$r->get_param('version_name');
        if(!$code || !preg_match('/^[0-9]+\.[0-9]+\.[0-9]+$/D',$name)) return new WP_Error('invalid_version','Ongeldige versie.', ['status'=>400]);
        self::available(['version_code'=>$code,'version_name'=>$name,'track'=>'internal','source'=>'verified_play_console']);
        return self::status();
    }
    static function available(array $release): void {
        $c=self::config(); $old=(int)($c['latest']['version_code']??0);
        if($release['version_code']<$old) return;
        if($release['version_code']>$old) { $c['latest']=$release+['available_at'=>gmdate('c')]; $c['delivery']=[]; update_option(self::OPTION,$c,false); }
        self::deliver();
    }
    static function deliver(): void {
        // Atomic lock prevents simultaneous poll/manual requests sending the same batch.
        if(!add_option('nenotv_release_mail_lock',time(),'',false)) {
            if((int)get_option('nenotv_release_mail_lock')<time()-600) delete_option('nenotv_release_mail_lock');
            return;
        }
        try {
            $c=self::config(); $latest=$c['latest']??null;
            if(!$latest || empty($c['play_url'])) return;
            foreach($c['recipients']??[] as $email) {
                $key=hash('sha256',strtolower($email)); $state=$c['delivery'][$key]??[];
                if(($state['state']??'')==='accepted_by_mailer' || ($state['state']??'')==='sending') continue;
                if((int)($state['attempts']??0)>=3) continue;
                if((int)($state['attempted_at']??0)>time()-3600) continue;
                $state=['state'=>'sending','attempts'=>1+(int)($state['attempts']??0),'attempted_at'=>time()];
                $c['delivery'][$key]=$state; update_option(self::OPTION,$c,false);
                $version=esc_html($latest['version_name']); $code=(int)$latest['version_code'];
                $subject='NenoTV-update beschikbaar: ' . $latest['version_name'];
                $html='<div style="font-family:Arial,sans-serif;max-width:620px;margin:auto;color:#172033"><div style="background:#101827;color:white;padding:24px"><strong style="font-size:24px">NenoTV</strong></div><div style="padding:24px"><h2>Je NenoTV-update staat klaar</h2><p>Versie <strong>'.$version.' ('.$code.')</strong> is beschikbaar voor jouw interne testgroep in Google Play.</p><p>Open de link op je Android-apparaat met het Google-account waarmee je aan de test deelneemt. Kies daarna Bijwerken in Google Play. Het kan even duren voordat de update op jouw apparaat verschijnt.</p><p><a style="display:inline-block;background:#ffd400;color:#101827;padding:14px 20px;text-decoration:none;border-radius:8px" href="'.esc_url($c['play_url']).'">Open mijn NenoTV-update</a></p><p>Zie je nog geen update? Controleer of je het juiste testaccount gebruikt.</p><p>Groet,<br>NenoTV</p></div></div>';
                $ok=wp_mail($email,$subject,$html,['Content-Type: text/html; charset=UTF-8','From: NenoTV <testers@nenotv.com>','Reply-To: NenoTV <info@nenotv.com>']);
                $c['delivery'][$key]=$state+['finished_at'=>gmdate('c')];
                $c['delivery'][$key]['state']=$ok?'accepted_by_mailer':'failed';
                update_option(self::OPTION,$c,false);
            }
        } finally { delete_option('nenotv_release_mail_lock'); }
    }
    static function google(string $method,string $url,string $token,?array $body=null): array {
        $args=['method'=>$method,'timeout'=>20,'redirection'=>0,'sslverify'=>true,'headers'=>['Authorization'=>'Bearer '.$token,'Accept'=>'application/json']];
        if($body!==null){$args['headers']['Content-Type']='application/json';$args['body']=wp_json_encode((object)$body);}
        $r=wp_remote_request($url,$args);
        if(is_wp_error($r) || wp_remote_retrieve_response_code($r)<200 || wp_remote_retrieve_response_code($r)>=300) throw new RuntimeException('Google Play-verzoek mislukt.');
        return json_decode(wp_remote_retrieve_body($r),true)?:[];
    }
    static function token(array $body): array {
        $r=wp_remote_post('https://oauth2.googleapis.com/token',['timeout'=>20,'redirection'=>0,'sslverify'=>true,'body'=>$body]);
        $data=is_wp_error($r)?[]:json_decode(wp_remote_retrieve_body($r),true);
        if(is_wp_error($r) || wp_remote_retrieve_response_code($r)!==200 || empty($data['access_token'])) throw new RuntimeException('Play-aanmelding niet beschikbaar.');
        return $data;
    }
    static function client(): array {
        if(!class_exists('NenoTV_Tester_OAuth')) throw new RuntimeException('Google-client ontbreekt.');
        $c=get_option(NenoTV_Tester_OAuth::OPTION,[]);
        $secret=NenoTV_Tester_OAuth::decrypt($c['secret_enc']??'');
        if(empty($c['client_id']) || $secret==='') throw new RuntimeException('Google-client ontbreekt.');
        return ['client_id'=>$c['client_id'],'client_secret'=>$secret];
    }
    static function selectRelease(array $tracks,string $track): ?array {
        $latest=null;
        foreach($tracks as $t) {
            if(($t['track']??'')!==$track) continue;
            foreach($t['releases']??[] as $r) {
                if(($r['status']??'')!=='completed') continue;
                foreach($r['versionCodes']??[] as $v) {
                    if(!ctype_digit((string)$v) || (int)$v<=0) continue;
                    if(!$latest || (int)$v>$latest['version_code']) {
                        preg_match('/\b([0-9]+\.[0-9]+\.[0-9]+)\b/',(string)($r['name']??''),$m);
                        $latest=['version_code'=>(int)$v,'version_name'=>$m[1]??('code '.(int)$v),'track'=>$track,'source'=>'google_play_api'];
                    }
                }
            }
        }
        return $latest;
    }
    static function poll(): void {
        $a=get_option(self::AUTH,[]);
        if(empty($a['refresh_enc'])) { self::deliver(); return; }
        if(!add_option('nenotv_release_poll_lock',time(),'',false)) {
            if((int)get_option('nenotv_release_poll_lock')<time()-600) delete_option('nenotv_release_poll_lock');
            return;
        }
        $edit='';$token='';$base='https://androidpublisher.googleapis.com/androidpublisher/v3/applications/'.self::PACKAGE.'/edits';
        try {
            $data=self::token(self::client()+['grant_type'=>'refresh_token','refresh_token'=>NenoTV_Tester_OAuth::decrypt($a['refresh_enc'])]);
            $token=$data['access_token'];
            $edit=self::google('POST',$base,$token,[])['id']??'';
            if(!preg_match('/^[A-Za-z0-9_-]+$/D',$edit)) throw new RuntimeException('Play-edit ontbreekt.');
            $data=self::google('GET',$base.'/'.$edit.'/tracks',$token);
            $c=self::config(); $release=self::selectRelease($data['tracks']??[],$c['track']??'internal');
            if($release) self::available($release);
            $c=self::config();$c['last_poll']=gmdate('c');$c['last_error']='';update_option(self::OPTION,$c,false);$a['verified']=true;update_option(self::AUTH,$a,false);
        } catch(Throwable $e) {
            $a['verified']=false;update_option(self::AUTH,$a,false);$c=self::config();$c['last_poll']=gmdate('c');$c['last_error']='Play-controle mislukt; er is geen nieuwe release aangekondigd.';update_option(self::OPTION,$c,false);
        } finally {
            // Discard only our inspection edit. No commit, uploads, or track mutations.
            if($edit!=='' && $token!=='') { try { self::google('DELETE',$base.'/'.$edit,$token); } catch(Throwable $e) {} }
            delete_option('nenotv_release_poll_lock');
        }
    }
    static function page(): void {
        if(!current_user_can('manage_options')) return;
        $s=self::status();
        echo '<div class="wrap"><h1>NenoTV updateberichten</h1><p>Testgroep: intern. Ontvangers: '.(int)$s['recipient_count'].'.</p><p>Automatische Play-controle: '.($s['automatic_play_detection']?'verbonden':'Google Play nog verbinden').'</p><p>Laatste versie: '.esc_html($s['latest']['version_name']??'nog geen').'</p><p>De koppeling kondigt uitsluitend beschikbare interne testreleases aan. Hij uploadt en publiceert geen apps.</p><form method="post" action="'.esc_url(admin_url('admin-post.php')).'"><input type="hidden" name="action" value="nenotv_release_connect">';
        wp_nonce_field('nenotv_release_connect');submit_button('Google Play verbinden');echo '</form></div>';
    }
    static function connect(): void {
        if(!current_user_can('manage_options')) wp_die('Geen toegang.');
        check_admin_referer('nenotv_release_connect');
        $client=self::client();$state=bin2hex(random_bytes(32));$verifier=rtrim(strtr(base64_encode(random_bytes(48)),'+/','-_'),'=');
        set_transient('nenotv_release_state_'.hash('sha256',$state),['user'=>get_current_user_id(),'session'=>hash('sha256',wp_get_session_token()),'verifier'=>$verifier],600);
        $uri=NenoTV_Tester_OAuth::redirect_uri();
        $url='https://accounts.google.com/o/oauth2/v2/auth?'.http_build_query(['client_id'=>$client['client_id'],'redirect_uri'=>$uri,'response_type'=>'code','scope'=>self::SCOPE,'access_type'=>'offline','prompt'=>'consent select_account','state'=>$state,'code_challenge'=>rtrim(strtr(base64_encode(hash('sha256',$verifier,true)),'+/','-_'),'='),'code_challenge_method'=>'S256'],'','&',PHP_QUERY_RFC3986);
        wp_redirect($url);exit;
    }
    static function callback(): void {
        $state=(string)($_GET['state']??'');
        if(!preg_match('/^[a-f0-9]{64}$/D',$state)) return; // Existing group OAuth callback remains untouched.
        $key='nenotv_release_state_'.hash('sha256',$state);$saved=get_transient($key);
        if(!$saved) return;
        delete_transient($key);
        try {
            if(!current_user_can('manage_options') || $saved['user']!==get_current_user_id() || !hash_equals($saved['session'],hash('sha256',wp_get_session_token()))) throw new RuntimeException('Sessiefout.');
            $code=(string)($_GET['code']??'');if($code==='') throw new RuntimeException('Geen toestemming.');
            $data=self::token(self::client()+['grant_type'=>'authorization_code','code'=>$code,'redirect_uri'=>NenoTV_Tester_OAuth::redirect_uri(),'code_verifier'=>$saved['verifier']]);
            if(empty($data['refresh_token']) || !in_array(self::SCOPE,explode(' ',$data['scope']??''),true)) throw new RuntimeException('Play-toestemming ontbreekt.');
            update_option(self::AUTH,['refresh_enc'=>NenoTV_Tester_OAuth::encrypt($data['refresh_token']),'connected_at'=>gmdate('c')],false);
            self::poll();
        } catch(Throwable $e) { $c=self::config();$c['last_error']='Google Play niet verbonden. Controleer de toestemming en app-toegang.';update_option(self::OPTION,$c,false); }
        wp_safe_redirect(admin_url('admin.php?page=nenotv-update-messages'));exit;
    }
}
add_action('plugins_loaded',[NenoTV_Release_Notifications::class,'init'],30);
