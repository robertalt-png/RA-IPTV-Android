<?php
// Real candidate core, fake provider responses and isolated customer/device records.
function wp_json_encode($v,$flags=0) { return json_encode($v,$flags); }
function esc_url_raw($v) { return $v; }
function delete_option($k) { unset($GLOBALS['options'][$k]);return true; }
function delete_user_meta($id,$k) { unset($GLOBALS['user_meta'][$id][$k]);return true; }
function wp_http_validate_url($u) { return str_starts_with($u,'https://provider.example/'); }
function as_enqueue_async_action($hook,$args,...$rest) { $GLOBALS['tasks'][]=$args; }
function wp_safe_remote_get($u,$args) {
    if (!wp_http_validate_url($u)) return new WP_Error('blocked','Blocked',[]);
    parse_str(parse_url($u,PHP_URL_QUERY)??'',$q);
    $data=$GLOBALS['fixture'][$q['action']??'m3u']??null;
    if ($data===null) return new WP_Error('fixture_missing','Missing',[]);
    file_put_contents($args['filename'],$data);return ['status'=>200];
}
function wp_remote_retrieve_response_code($r) { return $r['status']; }
ob_start(); require __DIR__.'/test-review-access.php'; ob_end_clean();
$params=$freeParams+['account_scope'=>$freeDone->data['account_link']['account_id']];
function sources($p,$action='pull') { return NenoTV_Entitlement_Core::account_sources_request(new WP_REST_Request($p),$action); }
function catalog($p,$action='status') { return NenoTV_Entitlement_Core::account_catalog_request(new WP_REST_Request($p),$action); }
$GLOBALS['tasks']=[];$GLOBALS['fixture']=[];
check('Light empty source vault available without Pro',sources($params)->status===200&&sources($params)->data['revision']===0);
$source=['id'=>'base-fixture','type'=>'XTREAM','server'=>'https://provider.example/','username'=>'0','password'=>'FAKE-PROVIDER-PASSWORD','name'=>'My TV','enabled'=>true,'epg'=>'','epg_extra'=>[]];
$save=sources($params+['base_revision'=>0,'sources'=>[$source]],'push');
check('Light stores source without a paid licence',$save->status===200&&$save->data['revision']===1);
check('provider password encrypted at rest',!str_contains(json_encode($GLOBALS['user_meta'][17]),$source['password']));
check('no paid rows or Pro granted',$GLOBALS['wpdb']->ent===[]&&$GLOBALS['wpdb']->dev===[]&&app('refresh',$params['device_id'],$params['device_key'])->data['entitlement']['level']==='free');
check('queued basic package after source save',count($GLOBALS['tasks'])===1);
check('same-account source pull contains exact credentials',sources($params)->data['sources'][0]['password']===$source['password']);
$wrong=$params;$wrong['device_key']=str_repeat('Z',43);
check('wrong key denied',sources($wrong)->status===403);
$wrong=$params;$wrong['account_scope']=str_repeat('a',64);
check('different account denied',sources($wrong)->status===409);
$wrong=$params;$wrong['device_id']='unlinked';
check('unlinked device denied',sources($wrong)->status===403);
check('stale source write denied',sources($params+['base_revision'=>0,'sources'=>[]],'push')->status===409);
$invalid=$source;$invalid['server']='http://127.0.0.1/private';
check('private provider address denied',sources($params+['base_revision'=>1,'sources'=>[$invalid]],'push')->status===400);
$invalid=$source;$invalid['epg']='http://127.0.0.1/private';
check('private guide address denied',sources($params+['base_revision'=>1,'sources'=>[$invalid]],'push')->status===400);
check('duplicate source IDs denied',sources($params+['base_revision'=>1,'sources'=>[$source,$source]],'push')->status===400);
check('invalid write preserves saved vault',sources($params)->data['revision']===1&&count(sources($params)->data['sources'])===1);
foreach(['live','vod','series'] as $type) {
    $GLOBALS['fixture']['get_'.$type.'_categories']='[{"category_id":"1","category_name":"Fixture"}]';
    $action=$type==='series'?'get_series':'get_'.$type.'_streams';$rows=[];
    for($i=1;$i<=1500;$i++)$rows[]=[$type==='series'?'series_id':'stream_id'=>$i,'name'=>'Synthetic '.$type.' '.$i,'category_id'=>'1'];
    $GLOBALS['fixture'][$action]=json_encode($rows);
}
$body=$params+['source_id'=>$source['id']];
check('unfinished package not delivered',catalog($body,'download')->status===409);
while($GLOBALS['tasks']) { $args=array_shift($GLOBALS['tasks']);NenoTV_Entitlement_Core::catalog_build(...$args); }
$status=catalog($body);$manifest=$status->data;
check('whole basic package ready in shadow mode',$status->status===200&&$manifest['state']==='ready');
check('complete list includes 4500 synthetic items',array_sum($manifest['counts'])===4500);
check('public manifest exposes no vault or private paths',!isset($manifest['parts'])&&!isset($manifest['token'])&&!str_contains(json_encode($manifest),$source['password']));
$body['fingerprint']=$manifest['fingerprint'];$response=catalog($body,'download');
check('compressed download available to basic account',$response->status===200&&$response->headers['Content-Type']==='application/vnd.nenotv.catalog+gzip');
$key=private_call('catalog_key',-17,$source['id']);$job=get_option($key);
$bytes='';foreach($job['parts'] as $part)NenoTV_Catalog_Format::unseal(private_call('catalog_dir').'/'.$part['name'],private_call('catalog_secret'),static function($b)use(&$bytes){$bytes.=$b;});
check('complete package matches published checksum',strlen($bytes)===$manifest['bytes']&&hash('sha256',$bytes)===$manifest['sha256']);
check('compression reduces synthetic catalogue size',strlen($bytes)<array_sum(array_map('strlen',$GLOBALS['fixture'])));
$body['sha256']=$manifest['sha256'];check('basic device acknowledgement accepted',catalog($body,'ack')->status===200);
$wrong=$body;$wrong['source_id']='another-source';check('other source package denied',catalog($wrong,'download')->status===404);
$wrong=$body;$wrong['account_scope']=str_repeat('f',64);check('other account package denied',catalog($wrong,'download')->status===409);
$GLOBALS['users'][17]->roles=['administrator'];check('privileged account rejected by source API',sources($params)->status===403);$GLOBALS['users'][17]->roles=['customer'];
$GLOBALS['user_meta'][17]['_sunnyiptv_account_deleting']=1;check('deleting account cannot receive data',catalog($body)->status===403);unset($GLOBALS['user_meta'][17]['_sunnyiptv_account_deleting']);
$row=$GLOBALS['user_meta'][17]['_sunnyiptv_source_vault'];$GLOBALS['user_meta'][17]['_sunnyiptv_source_vault']['tag']='bad';
check('damaged encrypted vault fails closed',sources($params)->status===503);$GLOBALS['user_meta'][17]['_sunnyiptv_source_vault']=$row;
NenoTV_Entitlement_Core::account_setup_delete(17);
check('account cleanup deletes encrypted credentials',!isset($GLOBALS['user_meta'][17]['_sunnyiptv_source_vault']));
check('account cleanup removes package',get_option($key)===false);
check('deletion does not alter paid rights',$GLOBALS['wpdb']->ent===[]&&$GLOBALS['wpdb']->dev===[]);
echo json_encode(['status'=>'passed','checks'=>count($checks),'new_account_checks'=>count($checks)-82,'synthetic_items'=>4500,'no_network'=>true,'live_deployed'=>false],JSON_PRETTY_PRINT)."\n";
