<?php
define('ABSPATH', __DIR__);
$options=[]; $sent=[];
function add_action(...$args) {}
function get_option($key,$default=false) { global $options; return $options[$key]??$default; }
function update_option($key,$value,...$args) { global $options; $options[$key]=$value; return true; }
function add_option($key,$value,...$args) { global $options; if(isset($options[$key])) return false; $options[$key]=$value; return true; }
function delete_option($key) { global $options; unset($options[$key]); }
function esc_html($x) { return htmlspecialchars($x); }
function esc_url($x) { return htmlspecialchars($x); }
function wp_mail($to,$subject,$body,$headers) { global $sent; $sent[]=$to; return true; }
require __DIR__.'/../../server/nenotv-release-notifications.php';
function check($ok) { if(!$ok) throw new RuntimeException('Release notification assertion failed'); }
$tracks=[['track'=>'production','releases'=>[['status'=>'completed','versionCodes'=>['999']]]],['track'=>'internal','releases'=>[
 ['name'=>'94 (0.14.1)','status'=>'draft','versionCodes'=>['94']],
 ['name'=>'93 (0.14.0)','status'=>'completed','versionCodes'=>['93']],
 ['name'=>'95 (0.14.2)','status'=>'halted','versionCodes'=>['95']],
 ['name'=>'invalid','status'=>'completed','versionCodes'=>['bad','-1']]
]]];
$release=NenoTV_Release_Notifications::selectRelease($tracks,'internal');
check($release['version_code']===93 && $release['version_name']==='0.14.0');
check(NenoTV_Release_Notifications::selectRelease([],'internal')===null);
$options[NenoTV_Release_Notifications::OPTION]=['track'=>'internal','recipients'=>['tester@example.invalid'],'play_url'=>'https://play.google.com/apps/internaltest/4701743473688897800'];
NenoTV_Release_Notifications::available($release);
NenoTV_Release_Notifications::available($release);
check(count($sent)===1);
NenoTV_Release_Notifications::available(['version_code'=>92,'version_name'=>'0.13.12','track'=>'internal']);
check(count($sent)===1);
NenoTV_Release_Notifications::available(['version_code'=>94,'version_name'=>'0.14.1','track'=>'internal']);
check(count($sent)===2);
$options['nenotv_release_mail_lock']=time();
NenoTV_Release_Notifications::available(['version_code'=>95,'version_name'=>'0.14.2','track'=>'internal']);
check(count($sent)===2);
echo "6 release selection/deduplication checks passed\n";
