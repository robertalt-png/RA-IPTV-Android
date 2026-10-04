<?php
require_once __DIR__.'/nenotv-catalog-format.php';
/** Initial catalog bootstrap only. Subsequent maintenance stays on the device. */
trait NenoTV_Catalog_Package {
    private static $catalog_stream=null;
    public static function catalog_hooks(): void {
        add_action('nenotv_catalog_build',[__CLASS__,'catalog_build'],10,3);
        add_action('rest_api_init',[__CLASS__,'catalog_routes']);
        add_filter('rest_pre_serve_request',[__CLASS__,'catalog_serve'],20,4);
        add_action(self::CRON,[__CLASS__,'catalog_cleanup']);
    }
    public static function catalog_routes(): void {
        foreach(['status','download','ack'] as $action)register_rest_route(self::APP_NS,'/catalog/'.$action,['methods'=>'POST','permission_callback'=>'__return_true','callback'=>static fn($r)=>self::catalog_request($r,$action)]);
    }
    private static function catalog_key(int $ent,string $id): string {return 'nenotv_catalog_'.hash('sha256',$ent.'|'.$id);}
    private static function catalog_fingerprint(array $s): string {
        return hash('sha256',json_encode(array_intersect_key($s,array_flip(['type','server','username','password','m3u'])),JSON_UNESCAPED_SLASHES));
    }
    private static function catalog_secret(): string {return hash('sha256',wp_salt('auth').'|nenotv-catalog-v1',true);}
    private static function catalog_dir(): string {
        $base=realpath(sys_get_temp_dir());$root=realpath(ABSPATH);
        if(!$base||!$root||str_starts_with($base.DIRECTORY_SEPARATOR,$root.DIRECTORY_SEPARATOR))throw new RuntimeException('catalog_private_storage');
        $path=$base.'/nenotv-catalog-'.substr(hash_hmac('sha256',home_url(),self::catalog_secret()),0,24);
        if(is_link($path))throw new RuntimeException('catalog_private_storage');
        if(!is_dir($path)&&!mkdir($path,0700))throw new RuntimeException('catalog_private_storage');chmod($path,0700);
        if(!is_writable($path))throw new RuntimeException('catalog_private_storage');return $path;
    }
    private static function catalog_source(int $ent,string $id): ?array {foreach(self::load_source_vault($ent)['sources'] as $s)if($s['id']===$id)return $s;return null;}
    private static function catalog_schedule(int $ent,string $id,string $fingerprint): void {
        $args=[$ent,$id,$fingerprint];
        if(function_exists('as_enqueue_async_action'))as_enqueue_async_action('nenotv_catalog_build',$args,'nenotv-catalog',false);
        elseif(!wp_next_scheduled('nenotv_catalog_build',$args))wp_schedule_single_event(time()+1,'nenotv_catalog_build',$args);
    }
    private static function catalog_remove_files(array $job): void {
        if(empty($job['token'])||!preg_match('/^[a-f0-9]{32}$/',$job['token']))return;
        try{$dir=self::catalog_dir();foreach(glob($dir.'/'.$job['token'].'.*')?:[] as $p)if(is_file($p))unlink($p);}catch(Throwable $e){}
    }
    private static function catalog_queue_source(int $ent,array $s): array {
        $key=self::catalog_key($ent,$s['id']);$old=get_option($key,[]);$fp=self::catalog_fingerprint($s);
        if(is_array($old)&&($old['fingerprint']??'')===$fp){
            $state=$old['state']??'';$recent=($old['updated']??0)>time()-900;
            if($state==='failed'&&($old['updated']??0)>time()-120)return $old;
            if(in_array($state,['queued','building'],true)&&$recent)return $old;
            if($state==='ready'&&($old['updated']??0)>time()-86400){$present=true;foreach($old['parts'] as $p)if(!is_file(self::catalog_dir().'/'.$p['name']))$present=false;if($present)return $old;}
        }
        if(is_array($old))self::catalog_remove_files($old);
        $job=['state'=>'queued','fingerprint'=>$fp,'token'=>bin2hex(random_bytes(16)),'step'=>0,'counts'=>['live'=>0,'vod'=>0,'series'=>0],'categories'=>['live'=>0,'vod'=>0,'series'=>0],'parts'=>[],'updated'=>time()];
        update_option($key,$job,false);self::catalog_schedule($ent,$s['id'],$fp);return $job;
    }
    public static function catalog_sources_saved(int $ent,array $sources): void {
        try{
            $known=get_option('nenotv_catalog_accounts',[]);if(!is_array($known))$known=[];
            $previous=$known[(string)$ent]??[];$ids=array_column($sources,'id');
            foreach(array_diff($previous,$ids) as $id){$key=self::catalog_key($ent,$id);self::catalog_remove_files((array)get_option($key,[]));delete_option($key);}
            $known[(string)$ent]=$ids;update_option('nenotv_catalog_accounts',$known,false);
            foreach($sources as $s)if($s['enabled'])self::catalog_queue_source($ent,$s);
        }catch(Throwable $e){/* A catalog failure must not undo a saved source. Status can retry. */}
    }
    private static function catalog_fetch(string $url,string $path): void {
        if(!wp_http_validate_url($url))throw new RuntimeException('catalog_provider_unreachable');
        $r=wp_safe_remote_get($url,['timeout'=>30,'redirection'=>3,'stream'=>true,'filename'=>$path,'limit_response_size'=>NenoTV_Catalog_Format::MAX_BYTES+1,'headers'=>['Accept-Encoding'=>'identity','User-Agent'=>'NenoTV/0.14.3 Catalog']]);
        if(is_file($path))chmod($path,0600);
        if(is_wp_error($r)||wp_remote_retrieve_response_code($r)!==200||!is_file($path)||filesize($path)>NenoTV_Catalog_Format::MAX_BYTES)throw new RuntimeException('catalog_provider_unreachable');
    }
    public static function catalog_build(int $ent,string $id,string $fp): void {
        $key=self::catalog_key($ent,$id);$job=get_option($key,[]);
        if(!is_array($job)||($job['fingerprint']??'')!==$fp||!in_array($job['state']??'',['queued','building'],true))return;
        $lock=null;$raw='';$plain='';
        try{
            $source=self::catalog_source($ent,$id);if(!$source||!$source['enabled']||self::catalog_fingerprint($source)!==$fp)return;
            $dir=self::catalog_dir();$lock=fopen($dir.'/'.$job['token'].'.lock','c');if(!$lock||!flock($lock,LOCK_EX|LOCK_NB))return;
            // Re-read after lock: an overlapping task may already have advanced the stage.
            $job=get_option($key,[]);if(($job['fingerprint']??'')!==$fp||($job['state']??'')==='ready')return;
            $job['state']='building';$job['updated']=time();update_option($key,$job,false);
            $step=(int)$job['step'];$raw=$dir.'/'.$job['token'].'.raw';$plain=$dir.'/'.$job['token'].'.gz';
            $gz=gzopen($plain,'wb6');if(!$gz)throw new RuntimeException('catalog_storage');chmod($plain,0600);
            try{
                if($step===0)NenoTV_Catalog_Format::line($gz,['kind'=>'header','schema'=>1,'source_id'=>$id,'fingerprint'=>$fp]);
                if($source['type']==='M3U'){
                    self::catalog_fetch($source['m3u'],$raw);$groups=[];
                    $job['counts']['live']=NenoTV_Catalog_Format::m3u($raw,static function($entry)use($gz,&$groups){$groups[$entry['group']]=true;NenoTV_Catalog_Format::line($gz,['kind'=>'item','entry'=>$entry]);});
                    foreach(array_keys($groups) as $g)NenoTV_Catalog_Format::line($gz,['kind'=>'category','type'=>'live','id'=>$g,'name'=>$g]);$job['categories']['live']=count($groups);$last=true;
                }else{
                    $types=['live','vod','series'];$type=$types[intdiv($step,2)];$category=$step%2===0;
                    $action=$category?'get_'.$type.'_categories':($type==='live'?'get_live_streams':($type==='vod'?'get_vod_streams':'get_series'));
                    $url=rtrim($source['server'],'/').'/player_api.php?'.http_build_query(['username'=>$source['username'],'password'=>$source['password'],'action'=>$action],'','&',PHP_QUERY_RFC3986);
                    self::catalog_fetch($url,$raw);
                    $count=NenoTV_Catalog_Format::json_array($raw,static function($x)use($gz,$category,$type,$source){
                        if($category){$id=(string)($x['category_id']??'');if($id==='')throw new RuntimeException('catalog_category');NenoTV_Catalog_Format::line($gz,['kind'=>'category','type'=>$type,'id'=>$id,'name'=>(string)($x['category_name']??$id)]);}
                        else NenoTV_Catalog_Format::line($gz,['kind'=>'item','entry'=>NenoTV_Catalog_Format::xtream($x,$type,$source)]);
                    });
                    $category?$job['categories'][$type]=$count:$job['counts'][$type]=$count;$last=$step===5;
                }
                if(array_sum($job['counts'])>NenoTV_Catalog_Format::MAX_ROWS)throw new RuntimeException('catalog_rows');
                if($last)NenoTV_Catalog_Format::line($gz,['kind'=>'end','counts'=>$job['counts'],'categories'=>$job['categories']]);
            }finally{gzclose($gz);}
            $part=$job['token'].'.'.$step.'.sealed';$size=NenoTV_Catalog_Format::seal($plain,$dir.'/'.$part,self::catalog_secret());$job['parts'][]=['name'=>$part,'size'=>$size];$job['step']=$step+1;$job['updated']=time();
            // Never publish a package for credentials changed while this stage was fetching.
            $current=self::catalog_source($ent,$id);if(!$current||self::catalog_fingerprint($current)!==$fp)return;
            if($last){$hash=hash_init('sha256');$bytes=0;foreach($job['parts'] as $p){$bytes+=$p['size'];NenoTV_Catalog_Format::unseal($dir.'/'.$p['name'],self::catalog_secret(),static function($b)use($hash){hash_update($hash,$b);});}
                if($bytes>104857600)throw new RuntimeException('catalog_size');$job['sha256']=hash_final($hash);$job['bytes']=$bytes;$job['state']='ready';}
            update_option($key,$job,false);if(!$last)self::catalog_schedule($ent,$id,$fp);
        }catch(Throwable $e){$latest=get_option($key,[]);if(($latest['fingerprint']??'')===$fp){self::catalog_remove_files($job);$job['state']='failed';$job['error']='catalog_preparation_failed';$job['updated']=time();update_option($key,$job,false);}}
        finally{if($raw&&is_file($raw))unlink($raw);if($plain&&is_file($plain))unlink($plain);if(is_resource($lock)){flock($lock,LOCK_UN);fclose($lock);}}
    }
    public static function catalog_request(WP_REST_Request $r,string $action): WP_REST_Response {
        if(self::mode()!=='live')return self::json(['ok'=>false,'error'=>'pro_not_live'],403);
        if(strlen($r->get_body())>8192)return self::json(['ok'=>false,'error'=>'request_too_large'],413);
        $p=$r->get_json_params();if(!is_array($p))$p=[];$auth=self::source_device_auth($p);if(is_wp_error($auth))return self::wp_error_json($auth);
        $ent=(array)$auth['entitlement'];$scope=$p['account_scope']??null;
        if(!is_string($scope)||!hash_equals(self::email_hash((string)$ent['email']),$scope))return self::json(['ok'=>false,'error'=>'source_account_changed'],409);
        $id=$p['source_id']??null;if(!is_string($id)||strlen($id)>80)return self::json(['ok'=>false,'error'=>'invalid_source'],400);
        try{
            $source=self::catalog_source((int)$ent['id'],$id);if(!$source||!$source['enabled'])return self::json(['ok'=>false,'error'=>'source_unavailable'],404);
            $key=self::catalog_key((int)$ent['id'],$id);$job=get_option($key,[]);
            if($action==='status'){
                $job=self::catalog_queue_source((int)$ent['id'],$source);$public=array_intersect_key($job,array_flip(['state','fingerprint','counts','categories','bytes','sha256','updated','step']));
                return self::json(['ok'=>true,'schema'=>1,'source_id'=>$id]+$public,200);
            }
            if(($job['state']??'')!=='ready'||($p['fingerprint']??'')!==($job['fingerprint']??'')||self::catalog_fingerprint($source)!==$job['fingerprint'])return self::json(['ok'=>false,'error'=>'catalog_not_ready'],409);
            if($action==='ack'){
                if(($p['sha256']??'')!==$job['sha256'])return self::json(['ok'=>false,'error'=>'catalog_checksum'],400);
                update_option('nenotv_catalog_ack_'.hash('sha256',(string)$auth['device']['device_id'].'|'.$id),['fingerprint'=>$job['fingerprint'],'updated'=>time()],false);return self::json(['ok'=>true],200);
            }
            // Verify every sealed frame before starting the HTTP body.
            foreach($job['parts'] as $part)NenoTV_Catalog_Format::unseal(self::catalog_dir().'/'.$part['name'],self::catalog_secret(),static function($b){});
            self::$catalog_stream=$job;return new WP_REST_Response(null,200,['Content-Type'=>'application/vnd.nenotv.catalog+gzip','Content-Length'=>(string)$job['bytes'],'Cache-Control'=>'private, no-store','X-NenoTV-SHA256'=>$job['sha256'],'X-Content-Type-Options'=>'nosniff']);
        }catch(Throwable $e){return self::json(['ok'=>false,'error'=>'catalog_unavailable'],503);}
    }
    public static function catalog_serve($served,$result,$request,$server): bool {
        if($served||!self::$catalog_stream||$request->get_route()!=='/'.self::APP_NS.'/catalog/download'||$result->get_status()!==200)return (bool)$served;
        $job=self::$catalog_stream;self::$catalog_stream=null;foreach($job['parts'] as $part)NenoTV_Catalog_Format::unseal(self::catalog_dir().'/'.$part['name'],self::catalog_secret(),static function($bytes){echo $bytes;});return true;
    }
    public static function catalog_source_notice(array $ent,array $source,string $lang): string {
        $job=get_option(self::catalog_key((int)$ent['id'],$source['id']),[]);$state=$job['state']??'pending';
        $labels=['nl'=>['pending'=>'Mediapakket nog niet voorbereid','queued'=>'Mediapakket staat in de wachtrij','building'=>'Mediapakket wordt voorbereid','ready'=>'Mediapakket klaar voor je apparaat','failed'=>'Voorbereiden mislukt; de app kan opnieuw proberen'],
          'de'=>['pending'=>'Medienpaket noch nicht vorbereitet','queued'=>'Medienpaket wartet','building'=>'Medienpaket wird vorbereitet','ready'=>'Medienpaket bereit für dein Gerät','failed'=>'Vorbereitung fehlgeschlagen; die App kann erneut versuchen'],
          'en'=>['pending'=>'Media package not prepared yet','queued'=>'Media package queued','building'=>'Preparing media package','ready'=>'Media package ready for your device','failed'=>'Preparation failed; the app can retry']];
        $l=$labels[$lang]??$labels['en'];return '<p class="nv-catalog-status">'.esc_html($l[$state]??$l['pending']).(($state==='ready')?' · '.number_format_i18n(array_sum($job['counts'])).' items':'').'</p>';
    }
    public static function catalog_cleanup(): void {
        $accounts=get_option('nenotv_catalog_accounts',[]);foreach((array)$accounts as $ent=>$ids)foreach((array)$ids as $id){$key=self::catalog_key((int)$ent,$id);$j=get_option($key,[]);if(is_array($j)&&($j['updated']??0)<time()-7*86400){self::catalog_remove_files($j);delete_option($key);}}
    }
}
