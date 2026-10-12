<?php
if (!defined('ABSPATH')) exit;

/** Uses existing authentication and nonce-protected approval; never grants access from a URL. */
trait SunnyIPTV_Pro_Onboarding {
    private static function pro_onboarding_page(): void {
        if (!defined('DONOTCACHEPAGE')) define('DONOTCACHEPAGE', true);
        nocache_headers();
        header('Cache-Control: private, no-store, max-age=0');
        header('Referrer-Policy: no-referrer');
        header('X-Robots-Tag: noindex, nofollow');
        header('X-Frame-Options: DENY');
        header("Content-Security-Policy: frame-ancestors 'none'");
        do_action('litespeed_control_set_nocache', 'SunnyIPTV Pro activation');
        $code=self::pairing_code(is_string($_GET['code']??null)?$_GET['code']:'');
        $allowed=self::pairing_rate('web_lookup',(string)($_SERVER['REMOTE_ADDR']??'unknown'),15,5*MINUTE_IN_SECONDS);
        $session=$allowed?self::pairing_load($code):null;
        $lang=$session['lang']??(in_array($_GET['lang']??null,['nl','en','de'],true)?$_GET['lang']:'en');
        $t=static fn(string $nl,string $en,string $de):string=>$lang==='nl'?$nl:($lang==='de'?$de:$en);
        $valid=$session&&($session['flow']??'')==='pro';
        $trial=!empty($session['trial_intent']);
        $return='nenotv://pro-return'.($trial?'?home=1':'');
        if($valid&&!is_user_logged_in()&&class_exists('SunnyIPTV_Customer_Pairing'))SunnyIPTV_Customer_Pairing::remember_pro($code,$lang);
        status_header(200);
        if(isset($GLOBALS['wp_query']))$GLOBALS['wp_query']->is_404=false;
        get_header();
        echo '<main class="nv-pro-account nv-pro-onboarding"><h1>'.esc_html($trial?$t('Mijn SunnyIPTV Pro','My SunnyIPTV Pro','Mein SunnyIPTV Pro'):$t('Pro activeren','Activate Pro','Pro aktivieren')).'</h1>';
        echo '<style>.nv-pro-onboarding{max-width:560px;margin:32px auto;padding:20px}.nv-pro-onboarding form{max-width:100%}.nv-pro-onboarding input{max-width:100%;box-sizing:border-box}.nv-pro-onboarding .button,.nv-pro-onboarding button{min-height:48px}.nv-pro-onboarding details{margin:24px 0}.nv-pro-onboarding #customer_login{display:block}.nv-pro-onboarding #customer_login>div{float:none;width:100%}.nv-pro-onboarding .woocommerce-MyAccount-navigation{display:none}</style>';
        if(!$valid){
            echo '<p>'.esc_html($t('Deze aanvraag is verlopen. Ga terug naar de app en probeer opnieuw.','This request has expired. Return to the app and try again.','Diese Anfrage ist abgelaufen. Kehre zur App zurück und versuche es erneut.')).'</p>';
        }elseif(!is_user_logged_in()){
            echo '<h2>'.esc_html($t('Aanmelden','Sign in','Anmelden')).'</h2>';
            $target=home_url('/nenotv-pair/?code='.$code.'&lang='.$lang.'&flow=pro');
            $google=class_exists('NextendSocialLogin')&&isset(NextendSocialLogin::$enabledProviders['google']);
            if($google)echo do_shortcode('[nextend_social_login provider="google" redirect="'.esc_url($target).'"]');
            if($google)echo '<details><summary>'.esc_html($t('Met e-mail','Use email','Mit E-Mail')).'</summary>';
            echo do_shortcode('[woocommerce_my_account]');
            if($google)echo '</details>';
        }elseif(!self::free_customer(get_current_user_id())){
            echo '<p>'.esc_html($t('Gebruik je klantaccount, niet je beheeraccount.','Use your customer account, not your administrator account.','Verwende dein Kundenkonto, nicht dein Administratorkonto.')).'</p>';
            echo '<a class="button" href="'.esc_url(wp_logout_url(home_url('/nenotv-pair/?code='.$code.'&lang='.$lang.'&flow=pro'))).'">'.esc_html($t('Ander account','Another account','Anderes Konto')).'</a>';
        }elseif($session['state']==='pending'){
            echo '<h2>'.esc_html((string)$session['name']).'</h2>';
            $user=wp_get_current_user();
            echo '<fieldset><legend>'.esc_html($t('Account','Account','Konto')).'</legend><p>'.esc_html((string)$user->user_email).'</p></fieldset>';
            echo '<form method="post" action="'.esc_url(admin_url('admin-post.php')).'">';
            echo '<input type="hidden" name="action" value="nenotv_pair_approve"><input type="hidden" name="code" value="'.esc_attr($code).'">';
            echo wp_nonce_field('nenotv_pair_approve_'.$code,'_wpnonce',true,false);
            if($trial)echo '<input type="hidden" name="trial_start" value="1"><p>'.esc_html($t('Gratis proef. Geen betaling of betaald abonnement.','Free trial. No payment or paid subscription.','Kostenloser Test. Keine Zahlung und kein kostenpflichtiges Abonnement.')).'</p>';
            echo '<button type="submit">'.esc_html($trial?$t('Pro-proef activeren','Activate Pro trial','Pro-Test aktivieren'):$t('Ga door','Continue','Weiter')).'</button></form>';
        }elseif((int)($session['account_user_id']??$session['approved_user_id']??0)===get_current_user_id()){
            echo '<p>'.esc_html($t('Je kunt terug naar de app.','You can return to the app.','Du kannst zur App zurückkehren.')).'</p>';
            echo '<a class="button" href="'.esc_url($return).'">'.esc_html($t('Ga door','Continue','Weiter')).'</a>';
        }else{
            echo '<p>'.esc_html($t('Start opnieuw vanuit de app.','Start again from the app.','Starte erneut in der App.')).'</p>';
        }
        echo '<p><a href="'.esc_url($return.($trial?'&':'?').'stop=1').'">'.esc_html($t('Stop','Stop','Stop')).'</a></p></main>';
        get_footer();exit;
    }
}
