<?php
if (!defined('ABSPATH')) { exit; }

final class SunnyIPTV_Google_Play_Test_Rewards {
    public static function enabled(): bool {
        return get_option('sunnyiptv_google_play_only', '0') === '1';
    }

    public static function init(): void {
        add_action('admin_menu', [__CLASS__, 'menu'], 1000);
        add_action('admin_post_sunnyiptv_approve_play_reward', [__CLASS__, 'submit']);
        foreach (['nenotv_tester_apply','nenotv_tester_feedback','nenotv_ft_eval','nenotv_ft_step','nenotv_ft_manual_started'] as $action) {
            add_action('admin_post_' . $action, [__CLASS__, 'retired_form'], 0);
            add_action('admin_post_nopriv_' . $action, [__CLASS__, 'retired_form'], 0);
        }
    }

    public static function play_url(): string {
        $url = NenoTV_Google_Play_Testers::opt_in_url();
        $parts = wp_parse_url($url);
        return is_array($parts) && ($parts['scheme'] ?? '') === 'https'
            && ($parts['host'] ?? '') === 'play.google.com'
            && str_starts_with($parts['path'] ?? '', '/apps/testing/') ? $url : '';
    }

    public static function public_page(string $lang): string {
        $copy = [
            'nl'=>['Test SunnyIPTV via Google Play','De gesloten Android-test loopt via Google Play. Er is geen apart SunnyIPTV-testformulier of opdrachtenportaal. Geef je feedback via Google Play.','Na succesvolle deelname en onze goedkeuring ontvang je gratis 365 dagen Pro voor 1 apparaat op je SunnyIPTV-account. Geen betaling of automatische verlenging.','Deelnemen via Google Play','De deelname-link wordt voorbereid.','SunnyIPTV levert alleen de player, geen IPTV-content.'],
            'en'=>['Test SunnyIPTV through Google Play','The closed Android test runs through Google Play. There is no separate SunnyIPTV application form or task portal. Send feedback through Google Play.','After successful participation and our approval, you receive 365 days of Pro for 1 device on your SunnyIPTV account. No payment or automatic renewal.','Join through Google Play','The participation link is being prepared.','SunnyIPTV provides the player only, not IPTV content.'],
            'de'=>['SunnyIPTV über Google Play testen','Der geschlossene Android-Test läuft über Google Play. Es gibt kein separates SunnyIPTV-Anmeldeformular oder Aufgabenportal. Gib dein Feedback über Google Play.','Nach erfolgreicher Teilnahme und unserer Freigabe erhältst du 365 Tage Pro für 1 Gerät auf deinem SunnyIPTV-Konto. Keine Zahlung oder automatische Verlängerung.','Über Google Play teilnehmen','Der Teilnahme-Link wird vorbereitet.','SunnyIPTV bietet nur den Player, keine IPTV-Inhalte.'],
        ];
        $t = $copy[$lang] ?? $copy['en'];
        $html = '<section class="nv-section"><div class="nv-wrap"><h1>' . esc_html($t[0]) . '</h1><p>' . esc_html($t[1]) . '</p><p>' . esc_html($t[2]) . '</p>';
        $url = self::play_url();
        $html .= $url ? '<p><a class="nv-btn nv-btn-primary" href="' . esc_url($url) . '" target="_blank" rel="noopener">' . esc_html($t[3]) . '</a></p>' : '<p role="status">' . esc_html($t[4]) . '</p>';
        return $html . '<p>' . esc_html($t[5]) . '</p></div></section>';
    }

    public static function retired_form(): void {
        if (!self::enabled()) return;
        wp_die('Deze test loopt nu via Google Play. Het aparte SunnyIPTV-testformulier is gesloten.', '', ['response'=>410]);
    }

    public static function menu(): void {
        if (!self::enabled()) return;
        $parent = class_exists('WooCommerce') ? 'woocommerce' : 'tools.php';
        foreach (['nenotv-play-testers','nenotv-founding-dashboard','nenotv-founding-automation','nenotv-tester-oauth','edit.php?post_type=nenotv_tester'] as $slug) remove_submenu_page($parent, $slug);
        add_submenu_page($parent, 'Google Play-testbeloningen', 'Testbeloningen', 'manage_woocommerce', 'sunnyiptv-play-rewards', [__CLASS__, 'page']);
    }

    public static function approve(string $email, string $name, string $lang, bool $verified): array {
        if (!self::enabled() || !current_user_can('manage_woocommerce') || !$verified) return ['ok'=>false,'code'=>'verification_required'];
        $email = strtolower(trim($email));
        if (!is_email($email) || preg_match('/[\r\n]/', $email)) return ['ok'=>false,'code'=>'invalid_email'];
        global $wpdb;
        $lock = 'sunny_play_reward_' . substr(hash('sha256', $wpdb->prefix . '|' . $email), 0, 35);
        if ((int)$wpdb->get_var($wpdb->prepare('SELECT GET_LOCK(%s, 3)', $lock)) !== 1) return ['ok'=>false,'code'=>'busy'];
        try {
            $ids = get_posts(['post_type'=>'nenotv_tester','post_status'=>'publish','fields'=>'ids','posts_per_page'=>2,
                'meta_query'=>['relation'=>'OR',['key'=>'_nenotv_tester_reward_email','value'=>$email],['key'=>'_nenotv_tester_email','value'=>$email]]]);
            if (count($ids) > 1) return ['ok'=>false,'code'=>'duplicate_records'];
            $id = $ids ? (int)$ids[0] : 0;
            if ($id && NenoTV_FT_Thirty_Day_Rewards::applies($id)) return ['ok'=>false,'code'=>'existing_policy'];
            if ($id && class_exists('NenoTV_Tester_Account')) {
                $owner = NenoTV_Tester_Account::owner($id);
                $user = $owner ? get_userdata($owner) : false;
                if ($user && strtolower($user->user_email) !== $email) return ['ok'=>false,'code'=>'account_mismatch'];
            }
            if (!$id) {
                $id = wp_insert_post(['post_type'=>'nenotv_tester','post_status'=>'publish','post_title'=>$name !== '' ? sanitize_text_field($name) : $email], true);
                if (is_wp_error($id) || !$id) return ['ok'=>false,'code'=>'record_failed'];
                update_post_meta($id, '_nenotv_tester_email', $email);
                update_post_meta($id, '_nenotv_tester_name', sanitize_text_field($name));
                update_post_meta($id, '_nenotv_tester_language', in_array($lang, ['nl','en','de'], true) ? $lang : 'en');
            }
            if (get_post_meta($id, '_nenotv_tester_status', true) === 'rewarded') {
                NenoTV_Founding_Tester_Automation::process_completed_reward($id);
                return ['ok'=>true,'code'=>'already_rewarded','id'=>$id];
            }
            update_post_meta($id, '_nenotv_tester_google_play_only', 'yes');
            update_post_meta($id, '_nenotv_tester_google_play_verified_at', current_time('mysql', true));
            update_post_meta($id, '_nenotv_tester_completed_at', current_time('mysql', true));
            update_post_meta($id, '_nenotv_tester_reward_reserved', 'yes');
            update_post_meta($id, '_nenotv_tester_status', 'completed');
            NenoTV_Founding_Tester_Automation::process_completed_reward($id);
            $active = get_post_meta($id, '_nenotv_tester_status', true) === 'rewarded';
            return ['ok'=>$active,'code'=>$active ? 'rewarded' : 'reward_pending','id'=>$id];
        } finally {
            $wpdb->get_var($wpdb->prepare('SELECT RELEASE_LOCK(%s)', $lock));
        }
    }

    public static function retry_approved_rewards(): void {
        foreach (get_posts(['post_type'=>'nenotv_tester','post_status'=>'publish','fields'=>'ids','posts_per_page'=>100,
            'meta_query'=>[['key'=>'_nenotv_tester_google_play_only','value'=>'yes'],
                ['key'=>'_nenotv_tester_google_play_verified_at','compare'=>'EXISTS'],
                ['key'=>'_nenotv_tester_status','value'=>['completed','rewarded'],'compare'=>'IN']]]) as $id) {
            if (get_post_meta($id, '_nenotv_tester_google_play_verified_at', true) !== '')
                NenoTV_Founding_Tester_Automation::process_completed_reward((int)$id);
        }
    }

    public static function submit(): void {
        if (!current_user_can('manage_woocommerce')) wp_die('Not allowed.', '', ['response'=>403]);
        check_admin_referer('sunnyiptv_approve_play_reward');
        $result = self::approve((string)wp_unslash($_POST['email'] ?? ''), (string)wp_unslash($_POST['name'] ?? ''),
            (string)wp_unslash($_POST['language'] ?? 'nl'), !empty($_POST['verified']));
        wp_safe_redirect(add_query_arg('result', $result['code'], admin_url('admin.php?page=sunnyiptv-play-rewards')));
        exit;
    }

    public static function page(): void {
        if (!current_user_can('manage_woocommerce')) wp_die('Not allowed.');
        $result = sanitize_key((string)($_GET['result'] ?? ''));
        $messages = ['rewarded'=>'Het jaar Pro is actief. De bedankmail en beheerdersmelding worden automatisch verwerkt.',
            'already_rewarded'=>'Deze tester heeft het jaar Pro al ontvangen. Er is geen tweede jaar toegekend.',
            'reward_pending'=>'De beloning wacht op verwerking. Controleer de foutmelding hieronder.',
            'verification_required'=>'Bevestig eerst dat je de Google Play-deelname hebt gecontroleerd.',
            'invalid_email'=>'Vul een geldig accountadres in.', 'busy'=>'Probeer het nogmaals.',
            'duplicate_records'=>'Meerdere registraties gebruiken dit adres. Controleer de bestaande gegevens.',
            'account_mismatch'=>'De bestaande registratie hoort bij een ander accountadres.', 'record_failed'=>'De beloning kon niet worden geregistreerd.',
            'existing_policy'=>'Deze tester valt onder een bestaande beloningsregeling. Controleer eerst de bestaande afspraak.'];
        echo '<div class="wrap"><h1>Google Play-testbeloningen</h1>';
        if (isset($messages[$result])) echo '<div class="notice ' . (in_array($result,['rewarded','already_rewarded'],true) ? 'notice-success' : 'notice-error') . '"><p>' . esc_html($messages[$result]) . '</p></div>';
        echo '<p><a class="button" href="https://play.google.com/console/" target="_blank" rel="noopener">Open Google Play Console</a></p>';
        echo '<form method="post" action="' . esc_url(admin_url('admin-post.php')) . '"><input type="hidden" name="action" value="sunnyiptv_approve_play_reward">';
        wp_nonce_field('sunnyiptv_approve_play_reward');
        echo '<table class="form-table"><tr><th><label for="reward-email">SunnyIPTV-accountadres</label></th><td><input id="reward-email" type="email" class="regular-text" name="email" required></td></tr>';
        echo '<tr><th><label for="reward-name">Naam</label></th><td><input id="reward-name" name="name" class="regular-text"></td></tr>';
        echo '<tr><th><label for="reward-language">Taal van de bedankmail</label></th><td><select id="reward-language" name="language"><option value="nl">Nederlands</option><option value="en">English</option><option value="de">Deutsch</option></select></td></tr>';
        echo '<tr><th>Succesvol afgerond</th><td><label><input type="checkbox" name="verified" value="1" required> Ik heb de testdeelname in Google Play gecontroleerd en keur 1 jaar Pro goed.</label></td></tr></table>';
        submit_button('Goedkeuren en jaar Pro starten');
        echo '</form><h2>Toegekende beloningen</h2><table class="widefat striped"><thead><tr><th>Accountadres</th><th>Status</th><th>Pro geldig tot</th><th>Melding</th></tr></thead><tbody>';
        $ids = get_posts(['post_type'=>'nenotv_tester','post_status'=>'publish','fields'=>'ids','posts_per_page'=>100,'orderby'=>'ID','order'=>'DESC',
            'meta_query'=>[['key'=>'_nenotv_tester_status','value'=>['completed','rewarded'],'compare'=>'IN']]]);
        foreach ($ids as $id) {
            $email = get_post_meta($id,'_nenotv_tester_reward_email',true) ?: get_post_meta($id,'_nenotv_tester_email',true);
            $error = get_post_meta($id,'_nenotv_tester_reward_error',true) ?: get_post_meta($id,'_nenotv_tester_mail_error',true);
            echo '<tr><td>' . esc_html($email) . '</td><td>' . esc_html(get_post_meta($id,'_nenotv_tester_status',true) === 'rewarded' ? 'Pro actief' : 'Wacht op verwerking') . '</td><td>'
                . esc_html((string)get_post_meta($id,'_nenotv_tester_reward_expires_at',true)) . '</td><td>' . esc_html((string)$error) . '</td></tr>';
        }
        if (!$ids) echo '<tr><td colspan="4">Nog geen jaar-Pro-beloningen toegekend.</td></tr>';
        echo '</tbody></table></div>';
    }
}
SunnyIPTV_Google_Play_Test_Rewards::init();

