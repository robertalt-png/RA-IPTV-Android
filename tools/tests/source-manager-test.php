<?php
function esc_html($value){return htmlspecialchars((string)$value,ENT_QUOTES,'UTF-8');}
function esc_attr($value){return esc_html($value);}
function esc_url($value){return esc_attr($value);}
function admin_url($path){return 'https://nenotv.com/wp-admin/'.$path;}
function wp_nonce_field($action,$name,$referer,$echo){return '<input name="'.esc_attr($name).'" value="fixture-nonce">';}
function check_admin_referer($action){global $validNonce,$nonceAction;$nonceAction=$action;if(!$validNonce)wp_die('fixture-security-error');return true;}
function add_query_arg($key,$value,$url){return $url.'?'.urlencode($key).'='.urlencode($value);}
require __DIR__.'/pairing-backend.php';
$_GET=['lang'=>'en'];$logged=true;$userEmail='owner@example.invalid';$validNonce=true;
$options['nenotv_entitlement_mode']='live';$startChecks=$checks;
function source_vault(){global $p;return sources('pull',$p)->data;}
function post_source(string $action,array $values): string {
    global $nonceAction;
    $_POST=$values;
    try {NenoTV_Entitlement_Core::{'handle_source_'.$action}();throw new LogicException('Missing redirect');}
    catch(Redirect $e){return $e->getMessage();}
}
function source_error(string $action,array $values,string $expected): void {
    global $wpdb;
    $before=$wpdb->vaults;
    try{post_source($action,$values);throw new LogicException('Invalid source request accepted');}
    catch(RuntimeException $e){check(str_contains($e->getMessage(),$expected),'Unexpected error: '.$e->getMessage());}
    check($before===$wpdb->vaults,'Rejected request changed source vault');
}
$form=['source_type'=>'XTREAM','source_name'=>'Household','source_server'=>'https://provider.example.invalid','source_username'=>'fixture-user','source_password'=>'fixture-password','source_epg'=>'https://guide.example.invalid/xmltv','source_enabled'=>'1','source_priority'=>'2','base_revision'=>(string)source_vault()['revision']];
$logged=false;source_error('add',$form,'login');$logged=true;
$validNonce=false;source_error('add',$form,'fixture-security-error');$validNonce=true;
$userEmail='unrelated@example.invalid';source_error('add',$form,'cannot manage');$userEmail='owner@example.invalid';
$options['nenotv_entitlement_mode']='shadow';source_error('add',$form,'enabled service');$options['nenotv_entitlement_mode']='live';
$wpdb->entitlements[1]['status']='revoked';source_error('add',$form,'enabled service');$wpdb->entitlements[1]['status']='active';
foreach(['javascript:alert(1)','file:///etc/passwd','https://','https://bad host.invalid'] as $url)source_error('add',array_merge($form,['source_server'=>$url]),'Check the source');
foreach(['source_password','source_name','source_priority'] as $key)source_error('add',array_merge($form,[$key=>['bad']]),'Check the source');
source_error('add',array_merge($form,['source_type'=>'PORTAL']),'Check the source');
source_error('add',array_merge($form,['source_password'=>'']),'Check the source');
source_error('add',array_merge($form,['source_priority'=>'-1']),'Check the source');
source_error('add',array_merge($form,['base_revision'=>'']),'Sources changed');
$url=post_source('add',$form);check($url==='https://nenotv.com/my-account/?source_saved=1','Source redirect not canonical');
check($nonceAction==='nenotv_source_add_1','Add nonce was not account scoped');
$vault=source_vault();$saved=$vault['sources'][0];check($saved['password']==='fixture-password'&&$saved['priority']===2&&$saved['enabled'],'Added source config incorrect');
check(!str_contains(json_encode($wpdb->vaults),$saved['password']),'Added secret stored in plaintext');
$edit=['source_id'=>$saved['id'],'source_name'=>'Updated name','source_priority'=>'1','base_revision'=>(string)$vault['revision']];
$fields=new ReflectionMethod(NenoTV_Entitlement_Core::class,'source_form_fields');
$html=$fields->invoke(null,$wpdb->entitlements[1],$vault['revision'],'en',$saved);
foreach(['fixture-password','fixture-user','https://provider.example.invalid','https://guide.example.invalid'] as $secret)check(!str_contains($html,$secret),'Edit form leaked source credentials');
check(str_contains($html,'base_revision')&&str_contains($html,'source_enabled'),'Edit form missing revision or enabled field');
$url=post_source('edit',$edit);$after=source_vault();
check($nonceAction==='nenotv_source_edit_1_'.$saved['id'],'Edit nonce not source/account scoped');
check($after['sources'][0]['name']==='Updated name'&&$after['sources'][0]['password']===$saved['password']&&$after['sources'][0]['server']===$saved['server'],'Blank edit did not retain saved config');
check(!$after['sources'][0]['enabled'],'Disabling source did not persist');
source_error('edit',$edit,'Sources changed');
source_error('delete',['source_id'=>$saved['id'],'base_revision'=>$edit['base_revision']],'Sources changed');
$edit['base_revision']=(string)$after['revision'];
source_error('edit',array_merge($edit,['source_id'=>'other-account-source']),'no longer exists');
$tag=$wpdb->vaults[1]['tag'];$wpdb->vaults[1]['tag']=base64_encode(str_repeat('x',16));
source_error('edit',$edit,'could not be read');
$panel=new ReflectionMethod(NenoTV_Entitlement_Core::class,'source_account_panel');
$html=$panel->invoke(null,$wpdb->entitlements[1],'en');check(str_contains($html,'role="alert"')&&!str_contains($html,'<form'),'Unreadable vault offered a destructive blank form');
$wpdb->vaults[1]['tag']=$tag;$wpdb->writeFailure=true;source_error('edit',$edit,'could not be read or saved');$wpdb->writeFailure=false;
post_source('edit',array_merge($edit,['source_password'=>'replacement-password','source_clear_epg'=>'1','source_enabled'=>'1']));
$after=source_vault();check($after['sources'][0]['password']==='replacement-password'&&$after['sources'][0]['epg']===''&&$after['sources'][0]['epg_extra']===[]&&$after['sources'][0]['enabled'],'Secret replacement, EPG clear or enabling source failed');
$edit['base_revision']=(string)$after['revision'];$_GET=['lang'=>'nl'];
check(post_source('edit',$edit)==='https://nenotv.com/language/nl/mijn-account/?source_saved=1','Dutch edit redirected to wrong language');
$_GET=['lang'=>'en'];$after=source_vault();
post_source('delete',['source_id'=>$saved['id'],'base_revision'=>(string)$after['revision']]);check(source_vault()['sources']===[],'Deleting source did not reach app API');
$form=array_merge($form,['source_type'=>'M3U','source_m3u'=>'https://playlist.example.invalid/list.m3u','base_revision'=>(string)source_vault()['revision']]);
post_source('add',$form);$after=source_vault();$saved=$after['sources'][0];
check($saved['m3u']===$form['source_m3u']&&$saved['password']===''&&$saved['server']==='','M3U kept unrelated Xtream credentials');
$edit=['source_id'=>$saved['id'],'source_name'=>'M3U updated','source_priority'=>'0','source_enabled'=>'1','base_revision'=>(string)$after['revision']];
post_source('edit',$edit);check(source_vault()['sources'][0]['m3u']===$saved['m3u'],'Blank edit erased private M3U URL');
$html=$fields->invoke(null,$wpdb->entitlements[1],source_vault()['revision'],'en',source_vault()['sources'][0]);check(!str_contains($html,$saved['m3u']),'M3U URL appeared in edit HTML');
class SourceRaceDb extends TestDb {
    public bool $race=true;
    public function query($query){[$sql,$args]=$query;if($this->race&&str_starts_with($sql,'UPDATE')&&str_contains($sql,'nenotv_source_vault')){$this->race=false;$this->vaults[$args[5]]['revision']++;}return parent::query($query);}
}
$race=new SourceRaceDb();$race->devices=$wpdb->devices;$race->entitlements=$wpdb->entitlements;$race->vaults=$wpdb->vaults;$wpdb=$race;
$edit['base_revision']=(string)source_vault()['revision'];
try{post_source('edit',$edit);throw new LogicException('Race accepted');}catch(RuntimeException $e){check(str_contains($e->getMessage(),'Sources changed'),'Race did not report conflict');}
check(source_vault()['sources'][0]['name']==='M3U updated','Concurrent edit overwrote stored data');
$notice=new ReflectionMethod(NenoTV_Entitlement_Core::class,'source_account_notice');$options['nenotv_entitlement_mode']='shadow';
foreach(['en'=>'not publicly active','nl'=>'nog niet publiek actief','de'=>'noch nicht öffentlich aktiv'] as $lang=>$phrase)check(str_contains($notice->invoke(null,null,$lang),$phrase),'Missing localized prelaunch notice');
echo ($checks-$startChecks)." source manager/security checks passed\n";
