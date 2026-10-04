<?php
if (!defined('ABSPATH')) exit;

/** Device proof remains private to the app; the web code only requests account approval. */
trait NenoTV_Pairing {
    public static function pairing_hooks(): void {
        add_action('template_redirect', [__CLASS__, 'pairing_page'],0);
        add_action('admin_post_nenotv_pair_approve', [__CLASS__, 'pairing_approve']);
        add_action('admin_post_nopriv_nenotv_pair_approve', [__CLASS__, 'pairing_approve']);
        add_action('nenotv_pair_cleanup', [__CLASS__, 'pairing_cleanup']);
    }

    public static function pairing_routes(): void {
        foreach (['start','status','cancel'] as $action) {
            register_rest_route(self::APP_NS, '/pairing/'.$action, [
                'methods'=>'POST', 'permission_callback'=>'__return_true',
                'callback'=>static fn(WP_REST_Request $r)=>self::pairing_request($r,$action),
            ]);
        }
    }

    private static function pairing_code(string $value): string {
        $value=strtoupper(str_replace(['-',' '],'',trim($value)));
        return preg_match('/^(?:[A-Z2-9]{4}|[A-F0-9]{10})$/D',$value) ? $value : '';
    }

    private static function pairing_rate(string $kind,string $identity,int $limit,int $seconds): bool {
        $key='nenotv_pair_rl_'.hash_hmac('sha256',$kind.'|'.$identity,wp_salt('auth'));
        $count=(int)get_transient($key);
        if($count >= $limit)return false;
        set_transient($key,$count+1,$seconds);
        return true;
    }

    public static function pairing_cleanup(string $code): void {
        $code=self::pairing_code($code);
        if($code!=='')delete_option('nenotv_pair_'.$code);
    }

    private static function pairing_load(string $code): ?array {
        if($code==='')return null;
        $session=get_option('nenotv_pair_'.$code,null);
        if(!is_array($session))return null;
        if((int)($session['expires']??0)<=time()){
            self::pairing_cleanup($code);
            return null;
        }
        return $session;
    }

    private static function pairing_lock(string $code): bool {
        global $wpdb;
        return (int)$wpdb->get_var($wpdb->prepare('SELECT GET_LOCK(%s, 3)','nenotv_pair_'.substr(hash('sha256',$code),0,40)))===1;
    }

    private static function pairing_unlock(string $code): void {
        global $wpdb;
        $wpdb->get_var($wpdb->prepare('SELECT RELEASE_LOCK(%s)','nenotv_pair_'.substr(hash('sha256',$code),0,40)));
    }

    public static function pairing_request(WP_REST_Request $request,string $action): WP_REST_Response {
        if(self::mode()!=='live'&&!self::catalog_testing_enabled())return self::json(['ok'=>false,'error'=>'pro_not_live'],403);
        if(strlen((string)$request->get_body())>8192)return self::json(['ok'=>false,'error'=>'request_too_large'],413);
        $p=self::clean_app_payload($request);
        if(empty($p['device_id'])||strlen((string)($p['device_key']??''))<32)return self::json(['ok'=>false,'error'=>'invalid_device'],400);
        $raw=$request->get_json_params();if(!is_array($raw))$raw=[];
        $ip=(string)($_SERVER['REMOTE_ADDR']??'unknown');
        if(!self::pairing_rate('request',$ip,120,MINUTE_IN_SECONDS))return self::json(['ok'=>false,'error'=>'rate_limited'],429);
        if($action==='start'){
            if(!self::pairing_rate('start',$ip,10,10*MINUTE_IN_SECONDS))return self::json(['ok'=>false,'error'=>'rate_limited'],429);
            $existing=self::find_device($p['device_id']);
            if($existing&&!hash_equals((string)$existing['device_key_hash'],self::key_hash($p['device_key'])))return self::json(['ok'=>false,'error'=>'invalid_device'],403);
            $token=bin2hex(random_bytes(32));
            $session=[
                'device_id'=>$p['device_id'], 'key_hash'=>self::key_hash($p['device_key']),
                'public_device_id'=>$p['public_device_id']??'', 'platform'=>$p['platform']??'android',
                'name'=>substr(sanitize_text_field(is_scalar($raw['device_name']??null)?(string)$raw['device_name']:'NenoTV'),0,80),
                'token_hash'=>self::key_hash($token), 'expires'=>time()+300,
                'entitlement_id'=>0, 'state'=>'pending', 'lang'=>in_array($raw['lang']??'',['nl','en','de'],true)?$raw['lang']:'en',
            ];
            $code='';
            for($i=0;$i<5;$i++){
                $candidate=strtoupper(bin2hex(random_bytes(5)));
                if((int)($raw['pairing_version']??1)>=2){$alphabet='ABCDEFGHJKLMNPQRSTUVWXYZ23456789';$candidate='';for($j=0;$j<4;$j++)$candidate.=$alphabet[random_int(0,strlen($alphabet)-1)];}
                if(add_option('nenotv_pair_'.$candidate,$session,'',false)){$code=$candidate;break;}
            }
            if($code==='')return self::json(['ok'=>false,'error'=>'pairing_unavailable'],503);
            wp_schedule_single_event($session['expires']+60,'nenotv_pair_cleanup',[$code]);
            return self::json(['ok'=>true,'code'=>$code,'poll_token'=>$token,'expires_in'=>300,'poll_interval'=>5,
                'verification_url'=>home_url('/nenotv-pair/?code='.$code.'&lang='.(in_array($raw['lang']??'',['nl','en','de'],true)?$raw['lang']:'en'))],200);
        }
        $code=self::pairing_code(is_scalar($raw['code']??null)?(string)$raw['code']:'');
        $token=is_scalar($raw['poll_token']??null)?(string)$raw['poll_token']:'';
        if($code===''||!preg_match('/^[a-f0-9]{64}$/D',$token))return self::json(['ok'=>false,'error'=>'invalid_pairing'],403);
        if(!self::pairing_lock($code))return self::json(['ok'=>false,'error'=>'pairing_busy'],503);
        try{
            $session=self::pairing_load($code);
            if(!$session)return self::json(['ok'=>true,'state'=>'expired'],200);
            if(!hash_equals($session['device_id'],$p['device_id'])||!hash_equals($session['key_hash'],self::key_hash($p['device_key']))||!hash_equals($session['token_hash'],self::key_hash($token)))return self::json(['ok'=>false,'error'=>'invalid_pairing'],403);
            if($action==='cancel'){self::pairing_cleanup($code);return self::json(['ok'=>true,'state'=>'cancelled'],200);}
            if($action!=='status')return self::json(['ok'=>false,'error'=>'invalid_action'],400);
            if($session['state']==='pending')return self::json(['ok'=>true,'state'=>'pending'],200);
            $ent=self::find_by_id((int)$session['entitlement_id']);
            if(!is_array($ent)||!self::entitlement_is_active($ent)||!self::app_service_live($ent))return self::json(['ok'=>false,'error'=>'pro_inactive'],403);
            // The session carries only a hash; binding uses the proof just received from the app.
            if($session['state']!=='complete'){
                $p['platform']=substr($session['platform'].' / '.$session['name'],0,100);
                $bound=self::bind_device($ent,$p);
                if(empty($bound['ok']))return self::json($bound,409);
                $session['state']='complete';
                if(!update_option('nenotv_pair_'.$code,$session,false)){
                    self::pairing_cleanup($code);
                    return self::json(['ok'=>false,'error'=>'pairing_state_failed'],503);
                }
                self::log_event('device_pair',(string)$ent['reference'],(string)$p['device_id'],'success','Device paired after authenticated account approval.');
            }else{
                $device=self::find_device($p['device_id']);
                if(!$device||$device['status']!=='active'||(int)$device['entitlement_id']!==(int)$ent['id'])return self::json(['ok'=>false,'error'=>'device_not_linked'],403);
            }
            $payload=self::pro_payload($ent);
            $payload['used_devices']=self::count_active_devices((int)$ent['id']);
            $payload['free_devices']=max(0,(int)$ent['max_devices']-$payload['used_devices']);
            return self::json(['ok'=>true,'state'=>'complete','entitlement'=>$payload],200);
        }finally{self::pairing_unlock($code);}
    }

    public static function pairing_approve(): void {
        if(!is_user_logged_in())auth_redirect();
        $code=self::pairing_code(is_scalar($_POST['code']??null)?(string)$_POST['code']:'');
        $nonce=is_scalar($_POST['_wpnonce']??null)?(string)$_POST['_wpnonce']:'';
        if($code===''||!wp_verify_nonce($nonce,'nenotv_pair_approve_'.$code))wp_die('Security check failed.');
        if(!self::app_service_live(self::current_user_entitlement()))wp_die('NenoTV pairing is not live.');
        if(!self::pairing_rate('approve',(string)get_current_user_id(),30,10*MINUTE_IN_SECONDS))wp_die('Please try again later.');
        if(!self::pairing_lock($code))wp_die('Please try again.');
        try{
            $session=self::pairing_load($code);
            if(!$session||$session['state']!=='pending')wp_die('This pairing code is expired or already approved.');
            $ent=self::current_user_entitlement();
            if(!is_array($ent)||!self::user_owns_entitlement($ent)||!self::entitlement_is_active($ent))wp_die('An active NenoTV account is required.');
            $session['entitlement_id']=(int)$ent['id'];$session['state']='approved';
            if(!update_option('nenotv_pair_'.$code,$session,false))wp_die('Approval could not be saved.');
        }finally{self::pairing_unlock($code);}
        set_transient('nenotv_pair_notice_'.get_current_user_id(),1,MINUTE_IN_SECONDS);
        wp_safe_redirect(home_url('/nenotv-pair/?approved=1&lang='.($session['lang']??'en')));exit;
    }

    private static function pairing_qr(string $url): string {
        static $loaded=false;
        $id='nv-qr-'.substr(hash('sha256',$url),0,12);
        $html='<div id="'.$id.'" aria-label="QR code" style="width:220px;max-width:100%;background:white;padding:8px;margin:12px 0"></div>';
        if(!$loaded){$html.='<script src="'.esc_url(plugins_url('qrcode.js',__FILE__)).'"></script>';$loaded=true;}
        return $html.'<script>(function(){if(typeof qrcode!=="function")return;var q=qrcode(0,"M");q.addData('.wp_json_encode($url,JSON_HEX_TAG|JSON_HEX_AMP|JSON_HEX_APOS|JSON_HEX_QUOT).');q.make();document.getElementById("'.$id.'").innerHTML=q.createSvgTag({cellSize:4,margin:16,scalable:true});})();</script>';
    }

    public static function pairing_page(): void {
        if(trim((string)parse_url($_SERVER['REQUEST_URI']??'',PHP_URL_PATH),'/')!=='nenotv-pair')return;
        nocache_headers();header('X-Robots-Tag: noindex, nofollow');
        header('X-Frame-Options: DENY');header("Content-Security-Policy: frame-ancestors 'none'");
        if(!is_user_logged_in())auth_redirect();
        status_header(200);
        if(isset($GLOBALS['wp_query']))$GLOBALS['wp_query']->is_404=false;
        $lang=is_string($_GET['lang']??null)&&in_array($_GET['lang'],['nl','en','de'],true)?$_GET['lang']:self::account_language();
        $s=$lang==='nl'?[
            'title'=>'Apparaat koppelen','code'=>'Koppelcode','find'=>'Doorgaan','approve'=>'Dit apparaat koppelen',
            'expired'=>'De code is verlopen of ongeldig.','pending'=>'Wacht op bevestiging in de app.','done'=>'Bevestigd. Ga terug naar NenoTV op uw apparaat.',
            'inactive'=>'Koppelen is nog niet beschikbaar voor dit account.',
        ]:($lang==='de'?[
            'title'=>'Gerät verbinden','code'=>'Verbindungscode','find'=>'Weiter','approve'=>'Dieses Gerät verbinden',
            'expired'=>'Der Code ist ungültig oder abgelaufen.','pending'=>'Warte auf Bestätigung in der App.','done'=>'Bestätigt. Kehre zur NenoTV-App zurück.',
            'inactive'=>'Die Verbindung ist für dieses Konto noch nicht verfügbar.',
        ]:[
            'title'=>'Link device','code'=>'Pairing code','find'=>'Continue','approve'=>'Link this device',
            'expired'=>'The code is invalid or expired.','pending'=>'Waiting for confirmation in the app.','done'=>'Approved. Return to NenoTV on your device.',
            'inactive'=>'Pairing is not available for this account yet.',
        ]);
        get_header();
        echo '<main class="nv-pro-account nv-pair-page"><h1>'.esc_html($s['title']).'</h1>';
        if(!empty($_GET['approved'])&&get_transient('nenotv_pair_notice_'.get_current_user_id())){
            delete_transient('nenotv_pair_notice_'.get_current_user_id());
            echo '<p>'.esc_html($s['done']).'</p>';
            $path=$lang==='nl'?'/language/nl/mijn-account/':($lang==='de'?'/language/de/mein-konto/':'/my-account/');
            $destination=add_query_arg('nenotv_setup','1',home_url($path)).'#nenotv-sources';
            echo '<a class="button" href="'.esc_url($destination).'">'.esc_html($lang==='nl'?'Kies uw tv-aanbod':($lang==='de'?'TV-Angebot auswählen':'Choose your TV source')).'</a>';
            echo '<script>location.replace('.wp_json_encode($destination,JSON_HEX_TAG|JSON_HEX_AMP|JSON_HEX_APOS|JSON_HEX_QUOT).');</script>';
        }
        elseif(!self::app_service_live(self::current_user_entitlement()))echo '<p>'.esc_html($s['inactive']).'</p>';
        else{
            $code=self::pairing_code(is_scalar($_GET['code']??null)?(string)$_GET['code']:'');
            if($code===''){
                echo '<p>'.esc_html($lang==='nl'?'Open NenoTV op uw apparaat. Scan de QR met uw telefooncamera, of vul hier de vier tekens van het app-scherm in.':($lang==='de'?'Öffne NenoTV. Scanne den QR-Code mit deiner Handykamera oder gib die vier Zeichen aus der App hier ein.':'Open NenoTV. Scan its QR with your phone camera, or enter the four characters shown in the app here.')).'</p><form class="nv-pair-form" method="get"><input type="hidden" name="lang" value="'.esc_attr($lang).'"><label for="nv-pair-code">'.esc_html($s['code']).'</label><input id="nv-pair-code" name="code" placeholder="— — — —" maxlength="11" required autocomplete="off" autocapitalize="characters" spellcheck="false"><button type="submit">'.esc_html($s['approve']).'</button></form>';
            }elseif(!self::pairing_rate('lookup',(string)get_current_user_id(),5,5*MINUTE_IN_SECONDS)||!self::pairing_rate('web_lookup',(string)($_SERVER['REMOTE_ADDR']??'unknown'),15,5*MINUTE_IN_SECONDS)){
                echo '<p>'.esc_html($s['expired']).'</p>';
            }else{
                $session=self::pairing_load($code);
                if(!$session)echo '<p>'.esc_html($s['expired']).'</p>';
                elseif($session['state']!=='pending')echo '<p>'.esc_html($s['pending']).'</p>';
                else{
                    // The QR was scanned in the app; this page only confirms the device.
                    echo '<h2>'.esc_html($session['name']).'</h2><p><code>'.esc_html(strlen($code)===4?$code:substr($code,0,5).'-'.substr($code,5)).'</code></p><p>'.esc_html($session['public_device_id']).'</p>';
                    echo '<form method="post" action="'.esc_url(admin_url('admin-post.php')).'">';
                    echo '<input type="hidden" name="action" value="nenotv_pair_approve"><input type="hidden" name="code" value="'.esc_attr($code).'">';
                    echo wp_nonce_field('nenotv_pair_approve_'.$code,'_wpnonce',true,false);
                    echo '<button type="submit">'.esc_html($s['approve']).'</button></form>';
                }
            }
        }
        echo '</main>';get_footer();exit;
    }
}

