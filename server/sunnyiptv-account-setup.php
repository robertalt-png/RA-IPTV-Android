<?php
if (!defined('ABSPATH')) exit;

/** Basic account source delivery. Paid module rights are never issued here. */
trait SunnyIPTV_Account_Setup {
    public static function account_setup_hooks(): void {
        add_action('template_redirect', [__CLASS__, 'account_setup_page'], 1);
        add_action('admin_post_sunnyiptv_source_save', [__CLASS__, 'account_setup_save']);
        add_action('rest_api_init', [__CLASS__, 'account_setup_routes']);
        add_action('delete_user', [__CLASS__, 'account_setup_delete'], 20);
    }

    public static function account_setup_routes(): void {
        foreach (['pull','push'] as $action) register_rest_route(self::APP_NS, '/account/sources/'.$action,
            ['methods'=>'POST','permission_callback'=>'__return_true','callback'=>static fn($r)=>self::account_sources_request($r,$action)]);
        foreach (['status','download','ack'] as $action) register_rest_route(self::APP_NS, '/account/catalog/'.$action,
            ['methods'=>'POST','permission_callback'=>'__return_true','callback'=>static fn($r)=>self::account_catalog_request($r,$action)]);
    }

    private static function account_setup_auth(array $p): array|WP_Error {
        $device=is_string($p['device_id']??null)?$p['device_id']:'';
        $key=is_string($p['device_key']??null)?$p['device_key']:'';
        if ($device==='' || strlen($device)>200 || strlen($key)<32 || strlen($key)>512) return new WP_Error('invalid_device','Invalid device',['status'=>400]);
        $record=self::free_device(['device_id'=>$device]);
        $uid=(int)($record['user_id']??0);
        if (!$record || !hash_equals((string)$record['key_hash'],self::key_hash($key)) || !self::free_customer($uid)
            || !isset(self::free_links($uid)[self::free_device_key($device)])) return new WP_Error('account_not_linked','Account unavailable',['status'=>403]);
        $scope=self::free_account_payload($uid,'account')['account_id'];
        if (!is_string($p['account_scope']??null) || !hash_equals($scope,$p['account_scope'])) return new WP_Error('source_account_changed','Account changed',['status'=>409]);
        return ['user_id'=>$uid,'storage_id'=>-$uid,'device'=>['device_id'=>$device],'scope'=>$scope];
    }

    private static function account_vault(int $uid): array {
        if (!self::free_customer($uid)) throw new RuntimeException('account_unavailable');
        $row=get_user_meta($uid,'_sunnyiptv_source_vault',true);
        if ($row==='' || $row===false) return ['revision'=>0,'sources'=>[]];
        if (!is_array($row) || !isset($row['revision'])) throw new RuntimeException('source_storage');
        return ['revision'=>(int)$row['revision'],'sources'=>self::vault_decrypt($row)];
    }

    private static function account_save_vault(int $uid,array $sources,int $base): array {
        if ($base<0 || !self::free_customer($uid)) throw new RuntimeException('account_unavailable');
        if (!self::account_source_rows_valid($sources)) throw new InvalidArgumentException('invalid_sources');
        if (!self::free_lock('account:'.$uid)) throw new RuntimeException('source_storage');
        try {
            $old=self::account_vault($uid);
            if ($old['revision']!==$base) throw new UnexpectedValueException('source_revision_conflict');
            $clean=self::sanitize_source_list($sources);
            if (count($clean)!==count($sources) || count(array_unique(array_column($clean,'id')))!==count($sources) || count($clean)>20) throw new InvalidArgumentException('invalid_sources');
            $revision=$base+1;
            $row=self::vault_encrypt($clean)+['revision'=>$revision];
            if (!update_user_meta($uid,'_sunnyiptv_source_vault',$row)) throw new RuntimeException('source_storage');
            self::catalog_sources_saved(-$uid,$clean);
            return ['revision'=>$revision,'sources'=>$clean];
        } finally { self::free_unlock('account:'.$uid); }
    }

    private static function account_source_rows_valid(array $sources): bool {
        if (count($sources)>20) return false;
        foreach ($sources as $row) {
            if (!is_array($row) || !is_string($row['id']??null) || $row['id']==='' || strlen($row['id'])>80
                || !in_array($row['type']??null,['M3U','XTREAM'],true)) return false;
            foreach (['name'=>120,'server'=>1000,'username'=>500,'password'=>500,'m3u'=>2000,'epg'=>2000] as $key=>$max)
                if (isset($row[$key]) && (!is_string($row[$key]) || strlen($row[$key])>$max)) return false;
            if ($row['type']==='M3U' && !wp_http_validate_url($row['m3u']??'')) return false;
            if ($row['type']==='XTREAM' && (!wp_http_validate_url($row['server']??'') || ($row['username']??'')==='' || ($row['password']??'')==='')) return false;
            if (($row['epg']??'')!=='' && !wp_http_validate_url($row['epg'])) return false;
            if (isset($row['epg_extra']) && (!is_array($row['epg_extra']) || count($row['epg_extra'])>8)) return false;
            foreach ($row['epg_extra']??[] as $url) if (!is_string($url) || strlen($url)>2000 || !wp_http_validate_url($url)) return false;
        }
        return true;
    }

    private static function account_migrate_legacy(int $uid): void {
        if ($uid!==get_current_user_id() || get_user_meta($uid,'_sunnyiptv_source_vault',true)!=='') return;
        $ent=self::current_user_entitlement();
        if (!is_array($ent) || !self::user_owns_entitlement($ent)) return;
        $legacy=self::load_source_vault((int)$ent['id']);
        if ($legacy['sources'] && self::account_source_rows_valid($legacy['sources'])) self::account_save_vault($uid,$legacy['sources'],0);
    }

    public static function account_sources_request(WP_REST_Request $r,string $action): WP_REST_Response {
        if (strlen($r->get_body())>1048576) return self::json(['ok'=>false,'error'=>'request_too_large'],413);
        $p=$r->get_json_params(); if (!is_array($p)) $p=[];
        if (!self::pairing_rate('account_sources',(string)($_SERVER['REMOTE_ADDR']??'unknown'),120,MINUTE_IN_SECONDS)) return self::json(['ok'=>false,'error'=>'rate_limited'],429);
        $auth=self::account_setup_auth($p); if (is_wp_error($auth)) return self::wp_error_json($auth);
        try {
            if ($action==='pull') $vault=self::account_vault($auth['user_id']);
            else {
                if (!is_array($p['sources']??null) || !is_int($p['base_revision']??null)) return self::json(['ok'=>false,'error'=>'invalid_sources'],400);
                $vault=self::account_save_vault($auth['user_id'],$p['sources'],$p['base_revision']);
            }
            return self::json(['ok'=>true]+$vault,200);
        } catch (UnexpectedValueException $e) { return self::json(['ok'=>false,'error'=>'source_revision_conflict'],409); }
        catch (InvalidArgumentException $e) { return self::json(['ok'=>false,'error'=>'invalid_sources'],400); }
        catch (RuntimeException $e) { return self::json(['ok'=>false,'error'=>'source_storage'],503); }
    }

    public static function account_catalog_request(WP_REST_Request $r,string $action): WP_REST_Response {
        if (strlen($r->get_body())>8192) return self::json(['ok'=>false,'error'=>'request_too_large'],413);
        $p=$r->get_json_params(); if (!is_array($p)) $p=[];
        if (!self::pairing_rate('account_catalog',(string)($_SERVER['REMOTE_ADDR']??'unknown'),120,MINUTE_IN_SECONDS)) return self::json(['ok'=>false,'error'=>'rate_limited'],429);
        $auth=self::account_setup_auth($p); if (is_wp_error($auth)) return self::wp_error_json($auth);
        return self::catalog_authenticated($p,$auth['storage_id'],$auth['device'],$action);
    }

    private static function account_setup_url(string $lang): string {
        return add_query_arg('lang',$lang,home_url('/sunnyiptv-setup/'));
    }

    public static function account_setup_page(): void {
        if (trim((string)parse_url($_SERVER['REQUEST_URI']??'',PHP_URL_PATH),'/')!=='sunnyiptv-setup') return;
        nocache_headers();header('X-Robots-Tag: noindex, nofollow');header('X-Frame-Options: DENY');
        if (!is_user_logged_in()) auth_redirect();
        $uid=get_current_user_id();if (!self::free_customer($uid)) wp_die('Please use a customer account.');
        $lang=is_string($_GET['lang']??null)&&in_array($_GET['lang'],['nl','en','de'],true)?$_GET['lang']:self::account_language();
        $nl=$lang==='nl';$de=$lang==='de';
        try { self::account_migrate_legacy($uid);$vault=self::account_vault($uid); }
        catch (RuntimeException $e) { wp_die('Unable to read your provider details. Please retry later.'); }
        status_header(200);if (isset($GLOBALS['wp_query'])) $GLOBALS['wp_query']->is_404=false;
        get_header();echo '<main class="nv-pro-account nv-setup-page"><h1>'.esc_html($nl?'Mijn SunnyIPTV':($de?'Mein SunnyIPTV':'My SunnyIPTV')).'</h1>';
        if (!empty($_GET['saved']) && !empty($vault['sources'])) {
            echo '<h2>'.esc_html($nl?'Je lijst wordt klaargemaakt':($de?'Deine Liste wird vorbereitet':'Preparing your list')).'</h2><p>'.esc_html($nl?'Je aanbiedergegevens zijn opgeslagen. De app ontvangt de complete lijst automatisch zodra deze klaar is.':($de?'Deine Anbieterdaten wurden gespeichert. Die App empfängt die vollständige Liste automatisch, sobald sie bereit ist.':'Your provider details are saved. The app receives the complete list automatically when it is ready.')).'</p>';
            echo '<a class="button" href="nenotv://setup?entry=website">'.esc_html($nl?'Terug naar SunnyIPTV':($de?'Zurück zu SunnyIPTV':'Return to SunnyIPTV')).'</a>';
            echo '<p>'.esc_html($nl?'Gebruik je een tv? Laat SunnyIPTV daar openstaan.':($de?'Auf dem Fernseher? Lass SunnyIPTV dort geöffnet.':'Using a TV? Leave SunnyIPTV open there.')).'</p>';
            echo '<script>setTimeout(function(){location.href="nenotv://setup?entry=website";},1200);</script>';
        } else {
            echo '<h2>'.esc_html($nl?'Vul je tv-aanbieder in':($de?'TV-Anbieter eingeben':'Enter your TV provider')).'</h2>';
            echo '<form class="nv-source-form" method="post" action="'.esc_url(admin_url('admin-post.php')).'"><input type="hidden" name="action" value="sunnyiptv_source_save"><input type="hidden" name="lang" value="'.esc_attr($lang).'"><input type="hidden" name="base_revision" value="'.(int)$vault['revision'].'">';
            echo wp_nonce_field('sunnyiptv_source_save_'.$uid,'_wpnonce',true,false);
            echo '<p><label>'.esc_html($nl?'Type verbinding':($de?'Verbindungstyp':'Connection type')).'<select name="source_type"><option value="XTREAM">Xtream Codes</option><option value="M3U">M3U</option></select></label></p>';
            echo '<div data-source-kind="XTREAM"><p><label>'.esc_html($nl?'Serveradres':($de?'Serveradresse':'Server address')).'<input name="source_server" type="url" maxlength="1000" placeholder="https://" required autocomplete="off"></label></p>';
            echo '<p><label>'.esc_html($nl?'Gebruikersnaam':($de?'Benutzername':'Username')).'<input name="source_username" type="text" maxlength="500" required autocomplete="off"></label></p>';
            echo '<p><label>'.esc_html($nl?'Wachtwoord van je tv-aanbieder':($de?'Passwort deines TV-Anbieters':'TV provider password')).'<input name="source_password" type="password" maxlength="500" required autocomplete="new-password"></label></p></div>';
            echo '<div data-source-kind="M3U" hidden><p><label>M3U URL<input name="source_m3u" type="url" maxlength="2000" required disabled autocomplete="off"></label></p></div>';
            echo '<p><label><input name="source_consent" type="checkbox" value="1" required> '.esc_html($nl?'Mijn SunnyIPTV mag met deze gegevens mijn lijst ophalen, mijn aanbiedergegevens versleuteld bewaren en de lijst naar mijn gekoppelde apparaten sturen.':($de?'Mein SunnyIPTV darf meine Liste abrufen, meine Anbieterdaten verschlüsselt speichern und die Liste meinen verbundenen Geräten bereitstellen.':'My SunnyIPTV may retrieve my list, store my provider details encrypted and deliver the list to my linked devices.')).'</label></p>';
            echo '<button type="submit">'.esc_html($nl?'Opslaan en lijst laden':($de?'Speichern und Liste laden':'Save and load list')).'</button></form>';
            echo '<script>(function(){var form=document.querySelector(".nv-source-form"),mode=form.querySelector("select");function update(){form.querySelectorAll("[data-source-kind]").forEach(function(box){var show=box.dataset.sourceKind===mode.value;box.hidden=!show;box.querySelectorAll("input").forEach(function(input){input.disabled=!show;});});}mode.addEventListener("change",update);update();})();</script>';
        }
        echo '</main>';get_footer();exit;
    }

    public static function account_setup_save(): void {
        if (!is_user_logged_in()) auth_redirect();
        $uid=get_current_user_id();if (!self::free_customer($uid)) wp_die('Please use a customer account.');
        check_admin_referer('sunnyiptv_source_save_'.$uid);
        if (self::source_post('source_consent',1)!=='1') self::source_fail('invalid');
        if (!self::pairing_rate('account_source_save',(string)$uid,10,MINUTE_IN_SECONDS)) wp_die('Please try again later.');
        $base=self::source_post('base_revision',18);if ($base==='' || !ctype_digit($base)) self::source_fail('conflict',409);
        $type=self::source_post('source_type',10);if (!in_array($type,['XTREAM','M3U'],true)) self::source_fail('invalid');
        try { $vault=self::account_vault($uid);$old=$vault['sources']; }
        catch (RuntimeException $e) { self::source_fail('storage',503); }
        $row=['id'=>$old[0]['id']??wp_generate_uuid4(),'type'=>$type,'name'=>'My TV','enabled'=>true,'priority'=>0,'updated_at'=>(int)round(microtime(true)*1000),'epg'=>'','epg_extra'=>[],'server'=>'','username'=>'','password'=>'','m3u'=>''];
        if ($type==='XTREAM') {
            $row['server']=trim(self::source_post('source_server',1000));$row['username']=trim(self::source_post('source_username',500));$row['password']=self::source_post('source_password',500);
            if (!self::source_url_valid($row['server']) || $row['username']==='' || $row['password']==='') self::source_fail('invalid');
        } else { $row['m3u']=trim(self::source_post('source_m3u',2000));if (!self::source_url_valid($row['m3u'])) self::source_fail('invalid'); }
        if ($old) {
            $same=true;foreach (['type','server','username','password','m3u'] as $field) if (($old[0][$field]??'')!==$row[$field]) $same=false;
            if ($same) foreach (['name','enabled','priority','epg','epg_extra'] as $field) if (isset($old[0][$field])) $row[$field]=$old[0][$field];
            $old[0]=$row;
        } else $old=[$row];
        try { self::account_save_vault($uid,$old,(int)$base); }
        catch (UnexpectedValueException $e) { self::source_fail('conflict',409); }
        catch (InvalidArgumentException $e) { self::source_fail('invalid'); }
        catch (RuntimeException $e) { self::source_fail('storage',503); }
        wp_safe_redirect(add_query_arg('saved','1',self::account_setup_url(self::source_language())));exit;
    }

    public static function account_setup_delete(int $uid): void {
        self::catalog_sources_saved(-$uid,[]);
        delete_user_meta($uid,'_sunnyiptv_source_vault');
    }
}
