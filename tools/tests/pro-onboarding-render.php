<?php
define('ABSPATH',__DIR__.'/');
define('MINUTE_IN_SECONDS',60);
$case=$argv[1]??'anonymous';
$session=['name'=>'QA phone','lang'=>'nl','state'=>'pending','flow'=>'pro','account_user_id'=>7];
if($case==='complete')$session['state']='complete';
if($case==='foreign'){$session['state']='complete';$session['account_user_id']=9;}
if($case==='expired')$session=null;
if($case==='legacy')$session['flow']='';
if($case==='trial')$session['trial_intent']=true;
if($case==='trial_complete'){$session['trial_intent']=true;$session['state']='complete';}
$_GET=['code'=>'AB23','flow'=>'pro','lang'=>'nl'];
function nocache_headers(){}function do_action(...$args){}function status_header(...$args){}function get_header(){}function get_footer(){}
function esc_html($s){return htmlspecialchars($s,ENT_QUOTES);}
function esc_attr($s){return esc_html($s);}function esc_url($s){return esc_html($s);}
function is_user_logged_in(){global $case;return $case!=='anonymous';}
function get_current_user_id(){return 7;}
function wp_get_current_user(){return (object)['user_email'=>'qa@example.invalid'];}
function home_url($s){return 'https://example.invalid'.$s;}function admin_url($s){return home_url('/wp-admin/'.$s);}
function wp_logout_url($s){return home_url('/logout');}
function wp_nonce_field($action,...$args){return '<input name="_wpnonce" value="fixture">';}
function do_shortcode($s){return str_contains($s,'nextend')?'<button>Google</button>':'<form>E-mail</form>';}
function wp_json_encode($value,...$flags){return json_encode($value,...$flags);}
class NextendSocialLogin{public static $enabledProviders=['google'=>true];}
class SunnyIPTV_Customer_Pairing{public static function remember_pro(...$args){}}
require __DIR__.'/../../server/sunnyiptv-pro-onboarding.php';
class Fixture {
    use SunnyIPTV_Pro_Onboarding;
    static function pairing_code($s){return $s==='AB23'?$s:'';}
    static function pairing_load($code){global $session;return $session;}
    static function pairing_rate(...$args){global $case;return $case!=='limited';}
    static function free_customer($id){global $case;return $case!=='administrator';}
    static function render(){self::pro_onboarding_page();}
}
Fixture::render();
