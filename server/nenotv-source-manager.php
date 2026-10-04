<?php
if (!defined('ABSPATH')) exit;

trait NenoTV_Source_Manager {
    private static function source_strings(string $lang): array {
        $all = [
            'en'=>['title'=>'My sources','help'=>'Add M3U or Xtream settings here. Saved credentials are encrypted and are not displayed again. A linked app must sync before changes reach the device.','edit'=>'Edit source','add'=>'Add source','name'=>'Name','user'=>'Username','password'=>'Password','save'=>'Save source','delete'=>'Delete source','enabled'=>'Enabled','priority'=>'Priority (0 first, 99 last)','keep'=>'Leave secret fields empty to keep their saved values.','clear'=>'Clear all saved EPG URLs','saved'=>'Source saved.','deleted'=>'Source removed from your account. A linked app must retrieve this change.','empty'=>'No sources saved yet.','format'=>'For M3U, enter the playlist URL. For Xtream, enter server, username and password. Use http:// or https:// for URLs.','invalid'=>'Check the source fields. Enter a name and valid M3U or Xtream settings.','conflict'=>'Sources changed since this page was opened. Refresh My NenoTV and try again.','storage'=>'Sources could not be read or saved. Existing data has not been replaced. Please retry later.','inactive'=>'Source sync requires an enabled service and active account access.','missing'=>'This source no longer exists. Refresh My NenoTV.','limit'=>'The maximum of 20 sources has been reached.','access'=>'You cannot manage these sources.','pair'=>'Link a device','pending'=>'Device pairing and source sync are prepared but are not publicly active yet.','guide'=>'Pairing instructions'],
            'nl'=>['title'=>'Mijn bronnen','help'=>'Voeg hier M3U- of Xtream-instellingen toe. Opgeslagen inloggegevens zijn versleuteld en worden niet opnieuw getoond. Een gekoppelde app moet synchroniseren voordat wijzigingen op het apparaat verschijnen.','edit'=>'Bron bewerken','add'=>'Bron toevoegen','name'=>'Naam','user'=>'Gebruikersnaam','password'=>'Wachtwoord','save'=>'Bron opslaan','delete'=>'Bron verwijderen','enabled'=>'Ingeschakeld','priority'=>'Prioriteit (0 eerst, 99 laatst)','keep'=>'Laat geheime velden leeg om de opgeslagen waarden te behouden.','clear'=>'Alle opgeslagen EPG-URL’s wissen','saved'=>'Bron opgeslagen.','deleted'=>'Bron uit je account verwijderd. Een gekoppelde app moet deze wijziging nog ophalen.','empty'=>'Er zijn nog geen bronnen opgeslagen.','format'=>'Vul voor M3U de playlist-URL in. Vul voor Xtream server, gebruikersnaam en wachtwoord in. Gebruik http:// of https:// voor URL’s.','invalid'=>'Controleer de bronvelden. Vul een naam en geldige M3U- of Xtream-gegevens in.','conflict'=>'Bronnen zijn gewijzigd sinds deze pagina werd geopend. Vernieuw Mijn NenoTV en probeer opnieuw.','storage'=>'Bronnen konden niet worden gelezen of opgeslagen. Bestaande gegevens zijn niet vervangen. Probeer later opnieuw.','inactive'=>'Bronsynchronisatie vereist een ingeschakelde dienst en actieve accounttoegang.','missing'=>'Deze bron bestaat niet meer. Vernieuw Mijn NenoTV.','limit'=>'Het maximum van 20 bronnen is bereikt.','access'=>'Je kunt deze bronnen niet beheren.','pair'=>'Apparaat koppelen','pending'=>'Apparaatkoppeling en bronsynchronisatie zijn voorbereid, maar nog niet publiek actief.','guide'=>'Uitleg over koppelen'],
            'de'=>['title'=>'Meine Quellen','help'=>'Füge hier M3U- oder Xtream-Einstellungen hinzu. Gespeicherte Zugangsdaten sind verschlüsselt und werden nicht erneut angezeigt. Eine verbundene App muss synchronisieren, bevor Änderungen das Gerät erreichen.','edit'=>'Quelle bearbeiten','add'=>'Quelle hinzufügen','name'=>'Name','user'=>'Benutzername','password'=>'Passwort','save'=>'Quelle speichern','delete'=>'Quelle entfernen','enabled'=>'Aktiviert','priority'=>'Priorität (0 zuerst, 99 zuletzt)','keep'=>'Lasse geheime Felder leer, um gespeicherte Werte zu behalten.','clear'=>'Alle gespeicherten EPG-URLs löschen','saved'=>'Quelle gespeichert.','deleted'=>'Quelle aus deinem Konto entfernt. Eine verbundene App muss die Änderung noch abrufen.','empty'=>'Noch keine Quellen gespeichert.','format'=>'Für M3U: Playlist-URL eingeben. Für Xtream: Server, Benutzername und Passwort eingeben. Nutze http:// oder https:// für URLs.','invalid'=>'Prüfe die Quellenfelder. Gib einen Namen und gültige M3U- oder Xtream-Daten ein.','conflict'=>'Quellen wurden seit dem Öffnen dieser Seite geändert. Aktualisiere Mein NenoTV und versuche es erneut.','storage'=>'Quellen konnten nicht gelesen oder gespeichert werden. Bestehende Daten wurden nicht ersetzt. Versuche es später erneut.','inactive'=>'Quellensynchronisierung benötigt einen aktivierten Dienst und aktiven Kontozugang.','missing'=>'Diese Quelle existiert nicht mehr. Aktualisiere Mein NenoTV.','limit'=>'Das Maximum von 20 Quellen ist erreicht.','access'=>'Du kannst diese Quellen nicht verwalten.','pair'=>'Gerät verbinden','pending'=>'Gerätekopplung und Quellensynchronisierung sind vorbereitet, aber noch nicht öffentlich aktiv.','guide'=>'Anleitung zur Kopplung'],
        ];
        return $all[$lang] ?? $all['en'];
    }

    private static function source_fail(string $key, int $status=400): void {
        $s=self::source_strings(self::account_language());
        wp_die(esc_html($s[$key]), 'NenoTV', ['response'=>$status]);
    }

    private static function source_post(string $key, int $max): string {
        $raw=$_POST[$key]??'';
        if (!is_string($raw)) self::source_fail('invalid');
        $value=wp_unslash($raw);
        if (strlen($value)>$max) self::source_fail('invalid');
        return $value;
    }

    private static function source_url_valid(string $url): bool {
        $parts=parse_url($url);
        return is_array($parts) && in_array(strtolower((string)($parts['scheme']??'')), ['http','https'], true)
            && !empty($parts['host']) && !preg_match('/[\x00-\x20\x7f]/', $url);
    }

    private static function source_form_fields(array $ent, int $revision, string $lang, ?array $source=null): string {
        $s=self::source_strings($lang); $edit=is_array($source); $action=$edit?'edit':'add';
        $sid=$edit?(string)$source['id']:'';
        $h='<form class="nv-source-form" method="post" action="'.esc_url(admin_url('admin-post.php')).'">';
        $h.='<input type="hidden" name="action" value="nenotv_source_'.$action.'"><input type="hidden" name="base_revision" value="'.esc_attr((string)$revision).'"><input type="hidden" name="lang" value="'.esc_attr($lang).'">';
        if ($edit) $h.='<input type="hidden" name="source_id" value="'.esc_attr($sid).'">';
        $h.=wp_nonce_field('nenotv_source_'.$action.'_'.(int)$ent['id'].($edit?'_'.$sid:''),'_wpnonce',true,false);
        $h.='<p><label>'.esc_html($s['name']).'<input required maxlength="120" name="source_name" type="text" value="'.esc_attr($edit?(string)$source['name']:'').'"></label></p>';
        if ($edit) $h.='<p><strong>'.esc_html((string)$source['type']).'</strong></p><p>'.esc_html($s['keep']).'</p>';
        else $h.='<p><label>Type<select name="source_type"><option value="XTREAM">Xtream</option><option value="M3U">M3U</option></select></label></p><p>'.esc_html($s['format']).'</p>';
        $type=$edit?(string)$source['type']:'';
        if (!$edit || $type==='XTREAM') {
            $h.='<p><label>Server<input name="source_server" type="url" maxlength="1000" autocomplete="off" placeholder="https://"></label></p>';
            $h.='<p><label>'.esc_html($s['user']).'<input name="source_username" type="text" maxlength="500" autocomplete="off"></label></p>';
            $h.='<p><label>'.esc_html($s['password']).'<input name="source_password" type="password" maxlength="500" autocomplete="new-password"></label></p>';
        }
        if (!$edit || $type==='M3U') $h.='<p><label>M3U URL<input name="source_m3u" type="url" maxlength="2000" autocomplete="off" placeholder="https://"></label></p>';
        $h.='<p><label>EPG URL<input name="source_epg" type="url" maxlength="2000" autocomplete="off" placeholder="https://"></label></p>';
        if ($edit) $h.='<p><label><input name="source_clear_epg" type="checkbox" value="1"> '.esc_html($s['clear']).'</label></p>';
        $h.='<p><label><input name="source_enabled" type="checkbox" value="1"'.(!$edit || !empty($source['enabled'])?' checked':'').'> '.esc_html($s['enabled']).'</label></p>';
        $h.='<p><label>'.esc_html($s['priority']).'<input name="source_priority" type="number" min="0" max="99" value="'.esc_attr((string)($source['priority']??0)).'" required></label></p>';
        return $h.'<button type="submit">'.esc_html($s['save']).'</button></form>';
    }

    private static function source_account_notice(?array $ent, string $lang): string {
        $s=self::source_strings($lang);
        $guide=$lang==='nl'?'/language/nl/installatie/qr-koppeling/':($lang==='de'?'/language/de/einrichtung/qr-kopplung/':'/setup/qr-pairing/');
        $h='<div class="nv-account-notice"><strong>'.esc_html($s['pair']).'</strong><p>';
        if (self::mode()==='live' && is_array($ent) && self::entitlement_is_active($ent)) {
            $h.='<a class="button" href="'.esc_url(add_query_arg('lang',$lang,home_url('/nenotv-pair/'))).'">'.esc_html($s['pair']).'</a>';
        } else $h.=esc_html(self::mode()==='live'?$s['inactive']:$s['pending']);
        return $h.'</p><a href="'.esc_url(home_url($guide)).'">'.esc_html($s['guide']).'</a></div>';
    }

    private static function source_account_panel(array $ent, string $lang): string {
        $s=self::source_strings($lang);
        $h='<div class="nv-source-vault"><h3>'.esc_html($s['title']).'</h3><p>'.esc_html($s['help']).'</p>';
        try {$vault=self::load_source_vault((int)$ent['id']);}
        catch (RuntimeException $e) {return $h.'<p role="alert">'.esc_html($s['storage']).'</p></div>';}
        if (!empty($_GET['source_saved'])) $h.='<p class="nv-account-notice" role="status">'.esc_html($s['saved']).'</p>';
        if (!empty($_GET['source_deleted'])) $h.='<p class="nv-account-notice" role="status">'.esc_html($s['deleted']).'</p>';
        if (!$vault['sources']) $h.='<p>'.esc_html($s['empty']).'</p>';
        foreach ($vault['sources'] as $source) {
            $sid=(string)$source['id'];
            $h.='<article class="nv-source-card"><h4>'.esc_html((string)$source['name']).'</h4><p>'.esc_html((string)$source['type']).' · '.esc_html($s['priority']).': '.(int)$source['priority'].'</p>';
            $h.='<details class="nv-source-add"><summary>'.esc_html($s['edit']).'</summary>'.self::source_form_fields($ent,(int)$vault['revision'],$lang,$source).'</details>';
            $h.='<form class="nv-source-delete" method="post" action="'.esc_url(admin_url('admin-post.php')).'"><input type="hidden" name="action" value="nenotv_source_delete"><input type="hidden" name="source_id" value="'.esc_attr($sid).'"><input type="hidden" name="base_revision" value="'.(int)$vault['revision'].'"><input type="hidden" name="lang" value="'.esc_attr($lang).'">';
            $h.=wp_nonce_field('nenotv_source_delete_'.(int)$ent['id'].'_'.$sid,'_wpnonce',true,false);
            $h.='<button type="submit">'.esc_html($s['delete']).'</button></form></article>';
        }
        return $h.'<details class="nv-source-add"><summary>'.esc_html($s['add']).'</summary>'.self::source_form_fields($ent,(int)$vault['revision'],$lang).'</details></div>';
    }

    public static function handle_source_edit(): void {self::handle_source_action('edit');}

    private static function handle_source_action(string $action): void {
        if (!is_user_logged_in()) auth_redirect();
        $ent=self::current_user_entitlement();
        if (!is_array($ent) || !self::user_owns_entitlement($ent)) self::source_fail('access',403);
        $sid=$action==='add'?'':trim(self::source_post('source_id',80));
        if ($action!=='add' && $sid==='') self::source_fail('invalid');
        check_admin_referer('nenotv_source_'.$action.'_'.(int)$ent['id'].($action==='add'?'':'_'.$sid));
        if (self::mode()!=='live' || !self::entitlement_is_active($ent)) self::source_fail('inactive',403);
        $base=self::source_post('base_revision',18);
        if ($base==='' || !ctype_digit($base)) self::source_fail('conflict',409);
        try {$vault=self::load_source_vault((int)$ent['id']);}
        catch (RuntimeException $e) {self::source_fail('storage',503);}
        if ((int)$base!==(int)$vault['revision']) self::source_fail('conflict',409);
        $sources=$vault['sources']; $index=null;
        foreach ($sources as $i=>$source) if ((string)$source['id']===$sid) {$index=$i;break;}
        if ($action!=='add' && $index===null) self::source_fail('missing',404);
        if ($action==='delete') {array_splice($sources,$index,1);}
        else {
            if ($action==='add' && count($sources)>=20) self::source_fail('limit');
            $row=$action==='edit'?$sources[$index]:['id'=>wp_generate_uuid4(),'type'=>strtoupper(self::source_post('source_type',10)),'epg_extra'=>[]];
            if (!in_array($row['type'],['M3U','XTREAM'],true)) self::source_fail('invalid');
            $row['name']=trim(sanitize_text_field(self::source_post('source_name',120)));
            if ($row['name']==='') self::source_fail('invalid');
            foreach (['server'=>1000,'username'=>500,'password'=>500,'m3u'=>2000,'epg'=>2000] as $field=>$max) {
                $value=self::source_post('source_'.$field,$max);
                if ($field!=='password') $value=trim($value);
                if ($action==='add' || $value!=='') $row[$field]=$value;
            }
            if (self::source_post('source_clear_epg',1)==='1') {$row['epg']='';$row['epg_extra']=[];}
            if (($row['epg']??'')!=='' && !self::source_url_valid($row['epg'])) self::source_fail('invalid');
            if ($row['type']==='M3U') {
                if (!self::source_url_valid((string)($row['m3u']??''))) self::source_fail('invalid');
                $row['server']=$row['username']=$row['password']='';
            } else {
                if (!self::source_url_valid((string)($row['server']??'')) || ($row['username']??'')==='' || ($row['password']??'')==='') self::source_fail('invalid');
                $row['m3u']='';
            }
            $priority=self::source_post('source_priority',2);
            if ($priority==='' || !ctype_digit($priority) || (int)$priority>99) self::source_fail('invalid');
            $row['priority']=(int)$priority;
            $row['enabled']=self::source_post('source_enabled',1)==='1';
            $row['updated_at']=(int)round(microtime(true)*1000);
            if ($action==='add') $sources[]=$row; else $sources[$index]=$row;
        }
        try {self::save_source_vault((int)$ent['id'],$sources,(int)$base);}
        catch (UnexpectedValueException $e) {self::source_fail('conflict',409);}
        catch (RuntimeException $e) {self::source_fail('storage',503);}
        $lang=self::account_language();
        $url=$lang==='nl'?'/language/nl/mijn-account/':($lang==='de'?'/language/de/mein-konto/':'/my-account/');
        wp_safe_redirect(add_query_arg($action==='delete'?'source_deleted':'source_saved','1',home_url($url))); exit;
    }
}
