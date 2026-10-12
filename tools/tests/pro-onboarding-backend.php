<?php
$checks=0;
function check($value,$message){global $checks;if(!$value)throw new RuntimeException($message);$checks++;}
foreach(['anonymous','customer','administrator','complete','foreign','expired','legacy','limited'] as $case){
    $command=escapeshellarg(PHP_BINARY).' '.escapeshellarg(__DIR__.'/pro-onboarding-render.php').' '.escapeshellarg($case);
    $html=shell_exec($command);
    check(is_string($html)&&str_contains($html,'Pro activeren'),$case.' did not render');
    check(str_contains($html,'pro-return?stop=1'),$case.' has no stop action');
    if($case==='anonymous'){
        check(str_contains($html,'Google')&&str_contains($html,'Met e-mail'),'One sign-in entry missing');
        check(!str_contains($html,'nenotv_pair_approve'),'Anonymous approval exposed');
    }elseif($case==='customer'){
        check(str_contains($html,'nenotv_pair_approve')&&str_contains($html,'_wpnonce'),'Signed approval lacks nonce');
        check(str_contains($html,'qa@example.invalid'),'Customer cannot see chosen account');
    }else{
        check(!str_contains($html,'nenotv_pair_approve'),$case.' can approve a device');
        if($case==='complete')check(str_contains($html,'href="nenotv://pro-return"'),'Completed flow has no app return');
    }
}
define('ABSPATH',__DIR__.'/');
function add_action(...$args){}function add_filter(...$args){}
function wp_salt($kind){return 'fixture';}function wp_json_encode($s){return json_encode($s);}
function add_query_arg($args,$url){return $url.'?'.http_build_query($args);}
function home_url($s){return 'https://example.invalid'.$s;}
require __DIR__.'/../../server/mu-plugins/sunnyiptv-customer-pairing.php';
$cookie=SunnyIPTV_Customer_Pairing::encode('AB23','nl',time()+600,'pro');
$context=SunnyIPTV_Customer_Pairing::decode($cookie);
check(($context['kind']??'')==='pro','Login lost Pro context');
check(str_contains(SunnyIPTV_Customer_Pairing::account_target($context),'flow=pro'),'Login returns to account dashboard');
check(SunnyIPTV_Customer_Pairing::decode($cookie.'tampered')===[],'Forged context accepted');
check(SunnyIPTV_Customer_Pairing::decode(SunnyIPTV_Customer_Pairing::encode('AB23','nl',time()-1,'pro'))===[],'Expired login context accepted');
echo $checks." simplified activation checks passed\n";
