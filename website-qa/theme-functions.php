<?php
if (!defined('ABSPATH')) { exit; }

function nenotv_setup() {
    add_theme_support('title-tag');
    add_theme_support('post-thumbnails');
    add_theme_support('woocommerce');
    add_theme_support('custom-logo', ['height'=>108,'width'=>108,'flex-height'=>true,'flex-width'=>true]);
    add_theme_support('html5', ['search-form','gallery','caption','style','script']);
    register_nav_menus(['primary' => __('Primary Navigation','nenotv')]);
}
add_action('after_setup_theme','nenotv_setup');

function nenotv_assets() {
    wp_enqueue_style('nenotv-fonts',get_template_directory_uri().'/assets/fonts/fonts.css',[],wp_get_theme()->get('Version'));
    wp_enqueue_style('nenotv-style',get_stylesheet_uri(),[],wp_get_theme()->get('Version'));
    wp_enqueue_script('nenotv-theme',get_template_directory_uri().'/assets/theme.js',[],wp_get_theme()->get('Version'),true);
}
add_action('wp_enqueue_scripts','nenotv_assets');

function nenotv_meta_description() {
    if (is_admin()) return;
    if (defined('WPSEO_VERSION') || class_exists('WPSEO_Options')) return;
    $description = '';
    if (is_front_page()) {
        $description = 'NenoTV is a modern IPTV Player for Live TV, EPG, movies and series using your own compatible IPTV source. NenoTV does not provide channels, subscriptions or playlists.';
    } elseif (is_singular()) {
        $post = get_queried_object();
        if ($post instanceof WP_Post) {
            $description = trim(wp_strip_all_tags($post->post_excerpt));
            if ($description === '') {
                $description = wp_trim_words(wp_strip_all_tags(strip_shortcodes($post->post_content)), 28, '');
            }
        }
    }
    if ($description === '') $description = get_bloginfo('description');
    if ($description !== '') {
        echo "\n<meta name=\"description\" content=\"" . esc_attr($description) . "\">\n";
        echo "<meta property=\"og:description\" content=\"" . esc_attr($description) . "\">\n";
        echo "<meta name=\"twitter:description\" content=\"" . esc_attr($description) . "\">\n";
    }
    $social_title = is_front_page() ? 'NenoTV – Modern IPTV Player' : wp_get_document_title();
    echo "<meta property=\"og:title\" content=\"" . esc_attr($social_title) . "\">\n";
    echo "<meta name=\"twitter:title\" content=\"" . esc_attr($social_title) . "\">\n";
    echo "<meta property=\"og:site_name\" content=\"" . esc_attr(get_bloginfo('name')) . "\">\n";
    echo "<meta property=\"og:type\" content=\"" . (is_singular() ? 'article' : 'website') . "\">\n";
    echo "<meta property=\"og:url\" content=\"" . esc_url((is_singular() ? get_permalink() : home_url('/'))) . "\">\n";
    $og_image = '';
    if (is_singular() && has_post_thumbnail()) {
        $img = wp_get_attachment_image_src(get_post_thumbnail_id(), 'large');
        if (is_array($img)) $og_image = $img[0];
    }
    if ($og_image !== '') {
        echo "<meta property=\"og:image\" content=\"" . esc_url($og_image) . "\">\n";
        echo "<meta name=\"twitter:image\" content=\"" . esc_url($og_image) . "\">\n";
    }
    echo "<meta name=\"twitter:card\" content=\"summary_large_image\">\n";
}
add_action('wp_head','nenotv_meta_description',2);

function nenotv_organization_schema_data(): array {
    $vat_id = (string)get_option('nenotv_vat_id', '');
    $org = [
        '@type' => 'Organization',
        '@id' => home_url('/#organization'),
        'name' => 'NenoTV',
        'url' => home_url('/'),
        'email' => 'info@nenotv.com',
        'parentOrganization' => [
            '@type' => 'Organization',
            'name' => 'Tube Beheer B.V.',
        ],
    ];
    if ($vat_id !== '') $org['parentOrganization']['vatID'] = $vat_id;
    return $org;
}

function nenotv_yoast_schema_graph($graph) {
    if (!is_array($graph)) return $graph;
    $org = nenotv_organization_schema_data();
    $org_id = $org['@id'];
    $has_org = false;

    foreach ($graph as &$piece) {
        if (!is_array($piece)) continue;
        $types = isset($piece['@type']) ? (array)$piece['@type'] : [];
        if (in_array('Organization', $types, true)) {
            $has_org = true;
        }
        if (in_array('WebSite', $types, true)) {
            if (empty($piece['publisher'])) $piece['publisher'] = ['@id' => $org_id];
            if (function_exists('nenotv_current_language')) {
                $lang = nenotv_current_language();
                $locales = ['en' => 'en-GB', 'nl' => 'nl-NL', 'de' => 'de-DE'];
                if (isset($locales[$lang])) $piece['inLanguage'] = $locales[$lang];
            }
            if (!empty($piece['description']) && is_string($piece['description'])) {
                $piece['description'] = html_entity_decode($piece['description'], ENT_QUOTES | ENT_HTML5, 'UTF-8');
            }
        }
        if (in_array('ImageObject', $types, true) && function_exists('nenotv_current_language')) {
            $lang = nenotv_current_language();
            $locales = ['en' => 'en-GB', 'nl' => 'nl-NL', 'de' => 'de-DE'];
            if (isset($locales[$lang])) $piece['inLanguage'] = $locales[$lang];

            $image_url = (string)($piece['url'] ?? $piece['contentUrl'] ?? '');
            if (strpos($image_url, 'NenoTV-Live-TV-en-EPG.') !== false) {
                $captions = [
                    'en' => 'NenoTV Help Center concept and diagnostics on television and smartphone',
                    'nl' => 'NenoTV Helpcentrum-concept en diagnostiek op televisie en smartphone',
                    'de' => 'NenoTV Hilfe-Center-Konzept und Diagnose auf Fernseher und Smartphone',
                ];
                if (isset($captions[$lang])) $piece['caption'] = $captions[$lang];
            } elseif (strpos($image_url, 'NenoTV-synchronisatie.') !== false) {
                $captions = [
                    'en' => 'NenoTV Live TV and EPG concept on a television',
                    'nl' => 'NenoTV-concept voor live-tv en EPG op een televisie',
                    'de' => 'NenoTV-Konzept für Live-TV und EPG auf einem Fernseher',
                ];
                if (isset($captions[$lang])) $piece['caption'] = $captions[$lang];
            } elseif (strpos($image_url, 'NenoTV-Help-Center.') !== false) {
                $captions = [
                    'en' => 'NenoTV movie library concept with fictional film artwork on a television',
                    'nl' => 'NenoTV-filmbibliotheekconcept met fictieve filmposters op een televisie',
                    'de' => 'NenoTV-Filmbibliothekskonzept mit fiktiven Filmplakaten auf einem Fernseher',
                ];
                if (isset($captions[$lang])) $piece['caption'] = $captions[$lang];
            } elseif (strpos($image_url, 'NenoTV-Movies-en-Series.') !== false) {
                $captions = [
                    'en' => 'NenoTV synchronization concept across television, tablet and smartphone',
                    'nl' => 'NenoTV-synchronisatieconcept op televisie, tablet en smartphone',
                    'de' => 'NenoTV-Synchronisierungskonzept auf Fernseher, Tablet und Smartphone',
                ];
                if (isset($captions[$lang])) $piece['caption'] = $captions[$lang];
            } elseif (strpos($image_url, 'nenotv-iptv-hero-v2.') !== false) {
                $captions = [
                    'en' => 'NenoTV IPTV Player on a smart TV with live TV, channel list and electronic programme guide',
                    'nl' => 'NenoTV IPTV-speler op een smart-tv met live-tv, zenderlijst en elektronische programmagids',
                    'de' => 'NenoTV IPTV-Player auf einem Smart-TV mit Live-TV, Senderliste und elektronischem Programmführer',
                ];
                if (isset($captions[$lang])) $piece['caption'] = $captions[$lang];
            } elseif (!empty($piece['caption']) && is_string($piece['caption']) && preg_match('/^NenoTV website visual (?:upload set|batch) \d+ image \d+$/i', $piece['caption'])) {
                $page_id = (int)get_queried_object_id();
                if ($page_id <= 0) $page_id = (int)get_the_ID();
                $title = $page_id > 0 ? trim(wp_strip_all_tags(html_entity_decode((string)get_the_title($page_id), ENT_QUOTES | ENT_HTML5, 'UTF-8'))) : '';
                if ($title !== '') {
                    if ($lang === 'nl') $piece['caption'] = 'NenoTV-visual voor ' . $title;
                    elseif ($lang === 'de') $piece['caption'] = 'NenoTV-Visual für ' . $title;
                    else $piece['caption'] = 'NenoTV visual for ' . $title;
                }
            }
        }
    }
    unset($piece);

    if (!$has_org) $graph[] = $org;
    return $graph;
}

function nenotv_schema_fallback() {
    if (is_admin() || defined('WPSEO_VERSION') || class_exists('WPSEO_Options')) return;
    $graph = [
        '@context' => 'https://schema.org',
        '@graph' => [nenotv_organization_schema_data()],
    ];
    echo "\n<script type=\"application/ld+json\">" . wp_json_encode($graph, JSON_UNESCAPED_SLASHES|JSON_UNESCAPED_UNICODE) . "</script>\n";
}

add_filter('wpseo_schema_graph','nenotv_yoast_schema_graph',20,1);
add_action('wp_head','nenotv_schema_fallback',20);

function nenotv_localized_yoast_breadcrumbs($links) {
    if (!is_array($links) || empty($links)) return $links;

    $lang = function_exists('nenotv_current_language') ? nenotv_current_language() : 'en';
    $home = function_exists('pll_home_url') ? pll_home_url($lang) : home_url('/');
    if (isset($links[0]) && is_array($links[0])) {
        $links[0]['url'] = $home;
        if ($lang === 'de') $links[0]['text'] = 'Startseite';
        elseif ($lang === 'nl') $links[0]['text'] = 'Home';
        else $links[0]['text'] = 'Home';
    }

    foreach ($links as &$link) {
        if (is_array($link) && isset($link['text']) && is_string($link['text'])) {
            $link['text'] = html_entity_decode($link['text'], ENT_QUOTES | ENT_HTML5, 'UTF-8');
        }
    }
    unset($link);
    return $links;
}
add_filter('wpseo_breadcrumb_links', 'nenotv_localized_yoast_breadcrumbs', 20, 1);

function nenotv_contextual_image_alt($attr, $attachment, $size, int $context_post_id = 0) {
    if (!is_array($attr) || !is_object($attachment)) return $attr;
    $id = (int)($attachment->ID ?? 0);
    if ($id <= 0) return $attr;

    if ($context_post_id <= 0 && isset($attr['data-nenotv-context-post'])) {
        $context_post_id = (int)$attr['data-nenotv-context-post'];
    }
    unset($attr['data-nenotv-context-post']);
    $page_id = $context_post_id > 0 ? $context_post_id : (int)get_queried_object_id();
    if ($page_id <= 0) $page_id = (int)get_the_ID();
    $lang = $context_post_id > 0 && function_exists('pll_get_post_language') ? (string)pll_get_post_language($page_id, 'slug') : (function_exists('nenotv_current_language') ? nenotv_current_language() : 'en');
    if (!in_array($lang, ['en', 'nl', 'de'], true)) $lang = 'en';
    $focus = $page_id > 0 ? trim((string)get_post_meta($page_id, '_yoast_wpseo_focuskw', true)) : '';
    $localized_alts = [
        106 => ['en' => 'WordPress image upload interface', 'nl' => 'WordPress-interface voor het uploaden van afbeeldingen', 'de' => 'WordPress-Oberfläche zum Hochladen von Bildern'],
        112 => ['en' => 'NenoTV illustration of settings transfer from an old device to a new device', 'nl' => 'NenoTV-illustratie van instellingen overzetten van een oud naar een nieuw apparaat', 'de' => 'NenoTV-Illustration zur Übertragung von Einstellungen vom alten auf ein neues Gerät'],
        113 => ['en' => 'NenoTV legal disclaimer diagram: player software, no channel package and user-provided sources', 'nl' => 'NenoTV-diagram bij de disclaimer: spelersoftware, geen zenderpakket en eigen bronnen', 'de' => 'NenoTV-Diagramm zum Haftungsausschluss: Player-Software, kein Senderpaket und eigene Quellen'],
        114 => ['en' => 'NenoTV partner and business concept diagram', 'nl' => 'NenoTV-diagram van het partner- en bedrijfsconcept', 'de' => 'NenoTV-Diagramm zum Partner- und Geschäftskonzept'],
        115 => ['en' => 'NenoTV account and device overview illustration', 'nl' => 'NenoTV-illustratie van het account- en apparatenoverzicht', 'de' => 'NenoTV-Illustration der Konto- und Geräteübersicht'],
        141 => ['en' => 'NenoTV EPG and XMLTV illustration with programme rows', 'nl' => 'NenoTV-illustratie van EPG en XMLTV met programmaregels', 'de' => 'NenoTV-Illustration von EPG und XMLTV mit Programmzeilen'],
        142 => ['en' => 'NenoTV installation diagram: download, install, open and pair', 'nl' => 'NenoTV-installatiediagram: downloaden, installeren, openen en koppelen', 'de' => 'NenoTV-Installationsdiagramm: herunterladen, installieren, öffnen und koppeln'],
        143 => ['en' => 'NenoTV device login illustration with pairing code and device confirmation', 'nl' => 'NenoTV-illustratie van apparaataanmelding met koppelcode en apparaatbevestiging', 'de' => 'NenoTV-Illustration der Geräteanmeldung mit Kopplungscode und Gerätebestätigung'],
        144 => ['en' => 'NenoTV playlist troubleshooting diagram covering server, HTTP response, authentication and streams', 'nl' => 'NenoTV-diagram voor playlistproblemen met server, HTTP-antwoord, authenticatie en streams', 'de' => 'NenoTV-Diagramm zur Playlist-Fehlersuche mit Server, HTTP-Antwort, Anmeldung und Streams'],
        145 => ['en' => 'NenoTV EPG troubleshooting illustration separating feed problems from channel matching', 'nl' => 'NenoTV-illustratie voor EPG-problemen met onderscheid tussen feedproblemen en zendermatching', 'de' => 'NenoTV-Illustration zur EPG-Fehlersuche mit getrennten Feed- und Senderzuordnungsproblemen'],
        146 => ['en' => 'NenoTV privacy choices diagram with local-only storage, preferences and encrypted sync', 'nl' => 'NenoTV-diagram van privacykeuzes met lokale opslag, voorkeuren en versleutelde synchronisatie', 'de' => 'NenoTV-Diagramm zu Datenschutzoptionen mit lokaler Speicherung, Einstellungen und verschlüsselter Synchronisierung'],
        147 => ['en' => 'NenoTV terms illustration distinguishing player software from user-provided content', 'nl' => 'NenoTV-illustratie bij de voorwaarden met onderscheid tussen spelersoftware en eigen content', 'de' => 'NenoTV-Illustration zu Nutzungsbedingungen mit getrennten Bereichen für Player-Software und eigene Inhalte'],
        148 => ['en' => 'NenoTV release notes illustration with an update timeline', 'nl' => 'NenoTV-illustratie van release-notes met een updatetijdlijn', 'de' => 'NenoTV-Illustration der Versionshinweise mit einer Update-Zeitleiste'],
        149 => ['en' => 'NenoTV comparison diagram of M3U playlists and Xtream-style API connections', 'nl' => 'NenoTV-vergelijkingsdiagram van M3U-playlists en Xtream-API-verbindingen', 'de' => 'NenoTV-Vergleichsdiagramm von M3U-Playlists und Xtream-API-Verbindungen'],
        150 => ['en' => 'NenoTV EPG matching diagram connecting channel IDs, matching rules and guide data', 'nl' => 'NenoTV-diagram voor EPG-matching met zender-ID\'s, matchingregels en gidsgegevens', 'de' => 'NenoTV-Diagramm zur EPG-Zuordnung mit Sender-IDs, Zuordnungsregeln und Programmdaten'],
        151 => ['en' => 'NenoTV H.264 and H.265 HEVC codec comparison illustration', 'nl' => 'NenoTV-illustratie met vergelijking van H.264- en H.265-HEVC-codecs', 'de' => 'NenoTV-Illustration zum Vergleich der Codecs H.264 und H.265 HEVC'],
        152 => ['en' => 'NenoTV diagram comparing playback on a phone and a television through a decoder', 'nl' => 'NenoTV-diagram dat afspelen op telefoon en televisie via een decoder vergelijkt', 'de' => 'NenoTV-Diagramm zum Vergleich der Wiedergabe auf Smartphone und Fernseher über einen Decoder'],
        153 => ['en' => 'NenoTV XMLTV tvg-id illustration matching channel rows to programme data', 'nl' => 'NenoTV-illustratie van XMLTV-tvg-id-matching tussen zenderregels en programmagegevens', 'de' => 'NenoTV-Illustration zur XMLTV-tvg-id-Zuordnung zwischen Senderzeilen und Programmdaten'],
        154 => ['en' => 'NenoTV compatibility matrix illustration with television, phone, tablet and desktop icons', 'nl' => 'NenoTV-illustratie van een compatibiliteitsmatrix met televisie-, telefoon-, tablet- en desktopiconen', 'de' => 'NenoTV-Illustration einer Kompatibilitätsmatrix mit Symbolen für Fernseher, Smartphone, Tablet und Desktop'],
        155 => ['en' => 'NenoTV error categories diagram: network, authentication, source, codec, EPG and device', 'nl' => 'NenoTV-diagram van foutcategorieën: netwerk, authenticatie, bron, codec, EPG en apparaat', 'de' => 'NenoTV-Diagramm der Fehlerkategorien: Netzwerk, Anmeldung, Quelle, Codec, EPG und Gerät'],
        156 => ['en' => 'NenoTV migration and restore concept illustration on a phone', 'nl' => 'NenoTV-conceptillustratie van migratie en herstel op een telefoon', 'de' => 'NenoTV-Konzeptillustration für Migration und Wiederherstellung auf einem Smartphone'],
        157 => ['en' => 'NenoTV contact and support concept illustration on a phone', 'nl' => 'NenoTV-conceptillustratie van contact en ondersteuning op een telefoon', 'de' => 'NenoTV-Konzeptillustration für Kontakt und Support auf einem Smartphone'],
        158 => ['en' => 'NenoTV security illustration with a lock and privacy reminders', 'nl' => 'NenoTV-beveiligingsillustratie met een slot en privacyadviezen', 'de' => 'NenoTV-Sicherheitsillustration mit Schloss und Datenschutzhinweisen'],
        159 => ['en' => 'NenoTV Android phone and tablet interface concept', 'nl' => 'NenoTV-interfaceconcept voor Android-telefoon en tablet', 'de' => 'NenoTV-Oberflächenkonzept für Android-Smartphone und Tablet'],
        160 => ['en' => 'NenoTV Fire TV interface concept', 'nl' => 'NenoTV-interfaceconcept voor Fire TV', 'de' => 'NenoTV-Oberflächenkonzept für Fire TV'],
        161 => ['en' => 'NenoTV Samsung TV interface concept', 'nl' => 'NenoTV-interfaceconcept voor Samsung TV', 'de' => 'NenoTV-Oberflächenkonzept für Samsung TV'],
        162 => ['en' => 'NenoTV Apple TV interface concept', 'nl' => 'NenoTV-interfaceconcept voor Apple TV', 'de' => 'NenoTV-Oberflächenkonzept für Apple TV'],
        163 => ['en' => 'NenoTV iPhone and iPad interface concept', 'nl' => 'NenoTV-interfaceconcept voor iPhone en iPad', 'de' => 'NenoTV-Oberflächenkonzept für iPhone und iPad'],
        165 => ['en' => 'NenoTV Portal and Stalker source login concept', 'nl' => 'NenoTV-concept voor aanmelden bij een Portal- of Stalker-bron', 'de' => 'NenoTV-Konzept zur Anmeldung an einer Portal- oder Stalker-Quelle'],
        166 => ['en' => 'NenoTV secure and private connection illustration with a lock', 'nl' => 'NenoTV-illustratie van een veilige privéverbinding met een slot', 'de' => 'NenoTV-Illustration einer sicheren privaten Verbindung mit Schloss'],
        1464 => ['en' => 'NenoTV concept visual with a viewer facing multiple media screens', 'nl' => 'NenoTV-conceptbeeld met een kijker voor meerdere mediaschermen', 'de' => 'NenoTV-Konzeptbild mit einer Person vor mehreren Medienbildschirmen'],
        1465 => ['en' => 'NenoTV television interface concept in a living room', 'nl' => 'NenoTV-interfaceconcept op een televisie in een woonkamer', 'de' => 'NenoTV-Oberflächenkonzept auf einem Fernseher im Wohnzimmer'],
        1466 => ['en' => 'NenoTV help interface concept on a computer and phone', 'nl' => 'NenoTV-helpinterfaceconcept op een computer en telefoon', 'de' => 'NenoTV-Hilfeoberflächenkonzept auf Computer und Smartphone'],
        1467 => ['en' => 'NenoTV installation concept on a laptop, tablet and phone', 'nl' => 'NenoTV-installatieconcept op laptop, tablet en telefoon', 'de' => 'NenoTV-Installationskonzept auf Laptop, Tablet und Smartphone'],
        1470 => ['en' => 'NenoTV television interface concept with media thumbnails and a remote control', 'nl' => 'NenoTV-televisieinterfaceconcept met mediathumbnails en een afstandsbediening', 'de' => 'NenoTV-Fernsehoberflächenkonzept mit Medienvorschauen und Fernbedienung'],
        1471 => ['en' => 'NenoTV plan comparison concept with three illustrated feature panels', 'nl' => 'NenoTV-concept voor planvergelijking met drie geïllustreerde functiepanelen', 'de' => 'NenoTV-Konzept zum Tarifvergleich mit drei illustrierten Funktionsbereichen'],
        1468 => [
            'en' => 'NenoTV Help Center concept and diagnostics on television and smartphone',
            'nl' => 'NenoTV Helpcentrum-concept en diagnostiek op televisie en smartphone',
            'de' => 'NenoTV Hilfe-Center-Konzept und Diagnose auf Fernseher und Smartphone',
        ],
        1469 => [
            'en' => 'NenoTV Live TV and EPG concept on a television',
            'nl' => 'NenoTV-concept voor live-tv en EPG op een televisie',
            'de' => 'NenoTV-Konzept für Live-TV und EPG auf einem Fernseher',
        ],
        1472 => [
            'en' => 'NenoTV movie library concept with fictional film artwork on a television',
            'nl' => 'NenoTV-filmbibliotheekconcept met fictieve filmposters op een televisie',
            'de' => 'NenoTV-Filmbibliothekskonzept mit fiktiven Filmplakaten auf einem Fernseher',
        ],
        1473 => [
            'en' => 'NenoTV synchronization concept across television, tablet and smartphone',
            'nl' => 'NenoTV-synchronisatieconcept op televisie, tablet en smartphone',
            'de' => 'NenoTV-Synchronisierungskonzept auf Fernseher, Tablet und Smartphone',
        ],
    ];
    if (isset($localized_alts[$id][$lang])) {
        $alt = (string)$localized_alts[$id][$lang];
        if ($focus !== '' && mb_stripos($alt, $focus, 0, 'UTF-8') === false) $alt = $focus . ': ' . $alt;
        $attr['alt'] = $alt;
        return $attr;
    }

    if (in_array($id, [522, 526], true)) {
        $alts = [
            'en' => 'NenoTV IPTV Player on a smart TV with live TV, channel list and electronic programme guide',
            'nl' => 'NenoTV IPTV-speler op een smart-tv met live-tv, zenderlijst en elektronische programmagids',
            'de' => 'NenoTV IPTV-Player auf einem Smart-TV mit Live-TV, Senderliste und elektronischem Programmführer',
        ];
        if (isset($alts[$lang])) {
            $alt = (string)$alts[$lang];
            if ($focus !== '' && mb_stripos($alt, $focus, 0, 'UTF-8') === false) $alt = $focus . ': ' . $alt;
            $attr['alt'] = $alt;
        }
        return $attr;
    }

    $stored_alt = trim((string)get_post_meta($id, '_wp_attachment_image_alt', true));
    $is_featured = $page_id > 0 && (int)get_post_thumbnail_id($page_id) === $id;
    $page_content = $page_id > 0 ? (string)get_post_field('post_content', $page_id) : '';
    $attachment_url = (string)wp_get_attachment_url($id);
    $attachment_file = $attachment_url !== '' ? basename((string)parse_url($attachment_url, PHP_URL_PATH)) : '';
    $is_inline = $page_content !== '' && (
        str_contains($page_content, 'wp-image-' . $id)
        || ($attachment_file !== '' && str_contains($page_content, $attachment_file))
    );
    if (!$is_featured && !$is_inline) return $attr;

    $title = $page_id > 0 ? trim(wp_strip_all_tags(html_entity_decode((string)get_the_title($page_id), ENT_QUOTES | ENT_HTML5, 'UTF-8'))) : '';
    $generic_alt = preg_match('/^NenoTV website visual (?:upload set|batch) \d+ image \d+$/i', $stored_alt) === 1;
    $alt = trim((string)($attr['alt'] ?? $stored_alt));

    if (($generic_alt || $alt === '') && $title !== '') {
        if ($lang === 'nl') $alt = 'NenoTV-visual voor ' . $title;
        elseif ($lang === 'de') $alt = 'NenoTV-Visual für ' . $title;
        else $alt = 'NenoTV visual for ' . $title;
    }
    if ($focus !== '' && mb_stripos($alt, $focus, 0, 'UTF-8') === false) {
        $alt = $focus . ': ' . $alt;
    }
    if ($alt !== '') $attr['alt'] = $alt;
    return $attr;
}
add_filter('wp_get_attachment_image_attributes', 'nenotv_contextual_image_alt', 20, 3);






function nenotv_supported_language_slugs(): array {
    return ['en','nl','de'];
}

function nenotv_current_language() {
    $allowed = nenotv_supported_language_slugs();
    $query = strtolower(sanitize_key((string)($_GET['lang'] ?? '')));
    if (in_array($query, $allowed, true)) return $query;

    // WooCommerce utility pages can be served through translated public URLs
    // while WordPress still starts from the default-language page ID. Prefer
    // the explicit /language/{slug}/ path before asking Polylang for context.
    $request_uri = (string)($_SERVER['REQUEST_URI'] ?? '');
    $request_path = (string)wp_parse_url($request_uri, PHP_URL_PATH);
    if ($request_path !== '' && preg_match('#^/language/(en|nl|de)(?:/|$)#i', $request_path, $m)) {
        return strtolower($m[1]);
    }

    // When WordPress renders a translated page outside the normal front-end
    // request context (for example REST previews and shortcode rendering),
    // prefer the language assigned to the current post.
    if (function_exists('pll_get_post_language')) {
        $post_id = get_the_ID();
        $post_lang = $post_id ? strtolower((string)pll_get_post_language($post_id, 'slug')) : '';
        if (in_array($post_lang, $allowed, true)) return $post_lang;
    }

    if (defined('REST_REQUEST') && REST_REQUEST && function_exists('WC') && WC()->session) {
        $session_lang = strtolower(sanitize_key((string)WC()->session->get('nenotv_checkout_language')));
        if (in_array($session_lang, $allowed, true)) return $session_lang;
    }

    if (function_exists('pll_current_language')) {
        $lang = strtolower((string)pll_current_language('slug'));
        if (in_array($lang, $allowed, true)) return $lang;
    }
    $ref = (string)($_SERVER['HTTP_REFERER'] ?? '');
    if ($ref !== '') {
        $ref_host = strtolower((string)wp_parse_url($ref, PHP_URL_HOST));
        $home_host = strtolower((string)wp_parse_url(home_url('/'), PHP_URL_HOST));
        if ($ref_host !== '' && hash_equals($home_host, $ref_host)) {
            $query_string = (string)wp_parse_url($ref, PHP_URL_QUERY);
            if ($query_string !== '') {
                $args = [];
                parse_str($query_string, $args);
                $ref_lang = strtolower(sanitize_key((string)($args['lang'] ?? '')));
                if (in_array($ref_lang, $allowed, true)) return $ref_lang;
            }
        }
    }
    return 'en';
}

function nenotv_home_url() {
    return function_exists('pll_home_url') ? pll_home_url(nenotv_current_language()) : home_url('/');
}

function nenotv_language_from_url($url = '') {
    $allowed = nenotv_supported_language_slugs();
    if ($url !== '') {
        $query = (string)wp_parse_url($url, PHP_URL_QUERY);
        if ($query !== '') {
            $args = [];
            parse_str($query, $args);
            $lang = strtolower(sanitize_key((string)($args['lang'] ?? '')));
            if (in_array($lang, $allowed, true)) return $lang;
        }
        $path = (string)wp_parse_url($url, PHP_URL_PATH);
        if ($path !== '' && preg_match('#/language/(en|nl|de)(?:/|$)#i', $path, $m)) {
            return strtolower($m[1]);
        }
    }

    $query_lang = strtolower(sanitize_key((string)($_GET['lang'] ?? '')));
    if (in_array($query_lang, $allowed, true)) return $query_lang;

    if (function_exists('pll_current_language')) {
        $lang = strtolower((string)pll_current_language('slug'));
        if (in_array($lang, $allowed, true)) return $lang;
    }

    if (function_exists('pll_get_post_language')) {
        $queried_id = get_queried_object_id();
        if ($queried_id) {
            $lang = strtolower((string)pll_get_post_language($queried_id, 'slug'));
            if (in_array($lang, $allowed, true)) return $lang;
        }
    }

    return 'en';
}

function nenotv_yoast_og_locale($locale, $presentation = null) {
    $url = '';
    if (is_object($presentation)) {
        if (!empty($presentation->canonical)) {
            $url = (string)$presentation->canonical;
        } elseif (!empty($presentation->open_graph_url)) {
            $url = (string)$presentation->open_graph_url;
        }
    }

    $locales = ['en'=>'en_GB','nl'=>'nl_NL','de'=>'de_DE'];
    return $locales[nenotv_language_from_url($url)] ?? $locale;
}
add_filter('wpseo_og_locale', 'nenotv_yoast_og_locale', 10, 2);

function nenotv_yoast_schema_language($language, $data = []) {
    $url = '';
    if (is_array($data)) {
        foreach (['url', '@id', 'contentUrl'] as $key) {
            if (!empty($data[$key]) && is_string($data[$key])) {
                $url = $data[$key];
                break;
            }
        }
    }

    $languages = ['en'=>'en-GB','nl'=>'nl-NL','de'=>'de-DE'];
    return $languages[nenotv_language_from_url($url)] ?? $language;
}
add_filter('wpseo_schema_piece_language', 'nenotv_yoast_schema_language', 10, 2);

function nenotv_t($key, $fallback = '') {
    static $translations = [
        'nl' => [
            'home'=>'NenoTV home','open_navigation'=>'Navigatie openen','primary_navigation'=>'Hoofdnavigatie','features'=>'Functies','pricing'=>'Light & Pro','devices'=>'Apparaten','setup'=>'Installatie','help'=>'Help','whats_new'=>'Wat is nieuw','my_nenotv'=>'Mijn NenoTV','cart'=>'Winkelwagen','free_app'=>'Android-test','product'=>'Product','learn'=>'Ontdek','supported_devices'=>'Ondersteunde apparaten','free_app_status'=>'Android-teststatus','help_center'=>'Helpcentrum','contact'=>'Contact','website_connection'=>'Websiteverbinding','active'=>'actief','in_testing'=>'in test','page_kicker'=>'NenoTV IPTV-speler','footer_about'=>'IPTV- en mediaspelersoftware voor tv en mobiel. NenoTV levert geen zenders, IPTV-abonnementen, afspeellijsten, films of series.','official_website'=>'Officiële website: nenotv.com','trust'=>'Vertrouwen','privacy'=>'Privacy','terms'=>'Voorwaarden','refund_policy'=>'Terugbetalingsbeleid','security'=>'Beveiliging','legal_disclaimer'=>'Juridische & inhoudsdisclaimer','company_details'=>'Bedrijfsgegevens','page_not_found'=>'Pagina niet gevonden','page_not_found_text'=>'Deze pagina is mogelijk verplaatst of nog niet beschikbaar. Gebruik het Helpcentrum of ga terug naar de NenoTV-homepagina.','search_title'=>'Zoeken','nothing_found'=>'Niets gevonden.'
        ],
        'de' => [
            'home'=>'NenoTV Startseite','open_navigation'=>'Navigation öffnen','primary_navigation'=>'Hauptnavigation','features'=>'Funktionen','pricing'=>'Light & Pro','devices'=>'Geräte','setup'=>'Einrichtung','help'=>'Hilfe','whats_new'=>'Neuigkeiten','my_nenotv'=>'Mein NenoTV','cart'=>'Warenkorb','free_app'=>'Android-Test','product'=>'Produkt','learn'=>'Entdecken','supported_devices'=>'Unterstützte Geräte','free_app_status'=>'Android-Teststatus','help_center'=>'Hilfe-Center','contact'=>'Kontakt','website_connection'=>'Website-Verbindung','active'=>'aktiv','in_testing'=>'im Test','page_kicker'=>'NenoTV IPTV-Player','footer_about'=>'IPTV- und Mediaplayer-Software für TV und Mobilgeräte. NenoTV bietet keine Sender, IPTV-Abonnements, Playlists, Filme oder Serien an.','official_website'=>'Offizielle Website: nenotv.com','trust'=>'Vertrauen','privacy'=>'Datenschutz','terms'=>'Nutzungsbedingungen','refund_policy'=>'Rückerstattungsrichtlinie','security'=>'Sicherheit','legal_disclaimer'=>'Rechtlicher & Inhalts-Hinweis','company_details'=>'Unternehmensangaben','page_not_found'=>'Seite nicht gefunden','page_not_found_text'=>'Diese Seite wurde möglicherweise verschoben oder ist noch nicht verfügbar. Nutze das Hilfe-Center oder kehre zur NenoTV-Startseite zurück.','search_title'=>'Suche','nothing_found'=>'Nichts gefunden.'
        ],
    ];
    $lang = nenotv_current_language();
    return $translations[$lang][$key] ?? ($fallback !== '' ? $fallback : $key);
}

function nenotv_setup_visuals_shortcode(): string {
    $lang = nenotv_current_language();
    if (function_exists('pll_get_post_language')) {
        $post_id = get_the_ID();
        $post_lang = $post_id ? strtolower((string)pll_get_post_language($post_id, 'slug')) : '';
        if (in_array($post_lang, ['en','nl','de'], true)) $lang = $post_lang;
    }
    $copy = [
        'en' => [
            'title'=>'See exactly where to enter your details',
            'intro'=>'These NenoTV example screens show the fields you use for M3U/M3U8, Xtream and optional XMLTV/EPG setup. The exact button name can differ slightly between app builds.',
            'example'=>'Example screen',
            'cards'=>[
                ['Choose your source type','Start by choosing the connection type that matches the details you received.','source-select.svg',['1'=>'M3U/M3U8','2'=>'Xtream','3'=>'EPG/XMLTV']],
                ['Xtream Codes','Enter the server address first, then your username and password. Save only after checking the protocol, port and spelling.','xtream-fields.svg',['1'=>'Server URL','2'=>'Username','3'=>'Password','4'=>'Save']],
                ['M3U / M3U8','Give the playlist a recognisable name and paste the complete playlist URL. Add an XMLTV URL only if your guide is separate.','m3u-fields.svg',['1'=>'Name','2'=>'Playlist URL','3'=>'Optional XMLTV','4'=>'Save']],
                ['EPG / XMLTV','Paste the XMLTV guide address, start the import and wait until NenoTV reports that the import has completed.','epg-save.svg',['1'=>'XMLTV URL','2'=>'Import','3'=>'Ready']],
            ],
            'privacy'=>'Never publish or send screenshots containing your real IPTV password, private token or credential-bearing playlist URL.'
        ],
        'nl' => [
            'title'=>'Zie precies waar je de gegevens invult',
            'intro'=>'Deze NenoTV-voorbeeldschermen laten zien welke velden je gebruikt voor M3U/M3U8, Xtream en een optionele XMLTV/EPG. De exacte knopnaam kan per appversie iets afwijken.',
            'example'=>'Voorbeeldscherm',
            'cards'=>[
                ['Kies je brontype','Kies eerst het verbindingstype dat hoort bij de gegevens die je hebt ontvangen.','source-select.svg',['1'=>'M3U/M3U8','2'=>'Xtream','3'=>'EPG/XMLTV']],
                ['Xtream Codes','Vul eerst het serveradres in en daarna je gebruikersnaam en wachtwoord. Controleer protocol, poort en spelling voordat je opslaat.','xtream-fields.svg',['1'=>'Server-URL','2'=>'Gebruikersnaam','3'=>'Wachtwoord','4'=>'Opslaan']],
                ['M3U / M3U8','Geef de playlist een herkenbare naam en plak de volledige playlist-URL. Voeg alleen een XMLTV-adres toe als je EPG apart wordt geleverd.','m3u-fields.svg',['1'=>'Naam','2'=>'Playlist-URL','3'=>'Optionele XMLTV','4'=>'Opslaan']],
                ['EPG / XMLTV','Plak het XMLTV-adres, start de import en wacht totdat NenoTV aangeeft dat de import is afgerond.','epg-save.svg',['1'=>'XMLTV-URL','2'=>'Importeren','3'=>'Klaar']],
            ],
            'privacy'=>'Deel of verstuur nooit screenshots waarop je echte IPTV-wachtwoord, privétoken of playlist-URL met inloggegevens zichtbaar is.'
        ],
        'de' => [
            'title'=>'Sieh genau, wo du deine Daten einträgst',
            'intro'=>'Diese NenoTV-Beispielansichten zeigen die Felder für M3U/M3U8, Xtream und optionales XMLTV/EPG. Die genaue Bezeichnung einer Schaltfläche kann je nach App-Version leicht abweichen.',
            'example'=>'Beispielansicht',
            'cards'=>[
                ['Quellentyp auswählen','Wähle zuerst den Verbindungstyp, der zu den erhaltenen Zugangsdaten passt.','source-select.svg',['1'=>'M3U/M3U8','2'=>'Xtream','3'=>'EPG/XMLTV']],
                ['Xtream Codes','Trage zuerst die Serveradresse und danach Benutzername und Passwort ein. Prüfe Protokoll, Port und Schreibweise vor dem Speichern.','xtream-fields.svg',['1'=>'Server-URL','2'=>'Benutzername','3'=>'Passwort','4'=>'Speichern']],
                ['M3U / M3U8','Vergib einen erkennbaren Namen und füge die vollständige Playlist-URL ein. Eine XMLTV-Adresse brauchst du nur bei separatem EPG.','m3u-fields.svg',['1'=>'Name','2'=>'Playlist-URL','3'=>'Optionales XMLTV','4'=>'Speichern']],
                ['EPG / XMLTV','Füge die XMLTV-Adresse ein, starte den Import und warte, bis NenoTV den erfolgreichen Abschluss meldet.','epg-save.svg',['1'=>'XMLTV-URL','2'=>'Importieren','3'=>'Fertig']],
            ],
            'privacy'=>'Veröffentliche oder versende niemals Screenshots mit deinem echten IPTV-Passwort, privaten Token oder einer Playlist-URL mit Zugangsdaten.'
        ],
    ];
    $s = $copy[$lang] ?? $copy['en'];
    $base = trailingslashit(get_template_directory_uri()).'assets/setup/';
    ob_start();
    ?>
    <section class="nv-setup-visuals" aria-labelledby="nv-setup-visuals-title">
      <div class="nv-setup-visuals-head">
        <p class="nv-eyebrow"><?php echo esc_html($s['example']); ?></p>
        <h2 id="nv-setup-visuals-title"><?php echo esc_html($s['title']); ?></h2>
        <p><?php echo esc_html($s['intro']); ?></p>
      </div>
      <div class="nv-setup-visual-grid">
        <?php foreach ($s['cards'] as $i=>$card): ?>
          <article class="nv-setup-visual-card">
            <div class="nv-setup-step-number"><?php echo esc_html((string)($i+1)); ?></div>
            <div class="nv-setup-visual-copy">
              <h3><?php echo esc_html($card[0]); ?></h3>
              <p><?php echo esc_html($card[1]); ?></p>
            </div>
            <img src="<?php echo esc_url($base.$card[2]); ?>" loading="lazy" decoding="async" alt="<?php echo esc_attr($s['example'].': '.$card[0]); ?>">
            <div class="nv-setup-callouts">
              <?php foreach ($card[3] as $number=>$label): ?>
                <span><b><?php echo esc_html((string)$number); ?></b><?php echo esc_html($label); ?></span>
              <?php endforeach; ?>
            </div>
          </article>
        <?php endforeach; ?>
      </div>
      <div class="nv-setup-privacy-note"><span aria-hidden="true">🔒</span><p><?php echo esc_html($s['privacy']); ?></p></div>
    </section>
    <?php
    return (string)ob_get_clean();
}
add_shortcode('nenotv_setup_visuals','nenotv_setup_visuals_shortcode');

function nenotv_light_pro_compare_shortcode(): string {
    $lang = function_exists('nenotv_current_language') ? nenotv_current_language() : 'en';
    if (function_exists('pll_get_post_language')) {
        $post_id = get_the_ID();
        $post_lang = $post_id ? strtolower((string)pll_get_post_language($post_id, 'slug')) : '';
        if (in_array($post_lang, ['en','nl','de'], true)) $lang = $post_lang;
    }

    $copy = [
        'en' => [
            'title'=>'Light or Pro after the trial',
            'intro'=>'Eligible new users start with 30 days of the complete Pro experience. After that, continue with Light or choose Pro. Starting the trial does not trigger an automatic payment.',
            'light_kicker'=>'CONTINUE FREE',
            'light_title'=>'NenoTV Light',
            'light'=>[
                'Keep using the same NenoTV app after the trial.',
                'Continue with the essential player experience for your own compatible source.',
                'Keep local settings, favourites, history and compatible viewing progress where the app and device support this.',
                'Upgrade to Pro later without reinstalling the app.',
            ],
            'pro_kicker'=>'ADVANCED EXPERIENCE',
            'pro_title'=>'NenoTV Pro',
            'pro'=>[
                'Your selected language is prioritised throughout the player experience.',
                'EPG is designed to prioritise your selected language when the source provides suitable guide data.',
                'Recording and catch-up controls where the source, platform, storage and permissions support them.',
                'Richer library organisation and advanced viewing controls.',
            ],
            'note'=>'Check the supported-device and download pages for current platform availability. Features marked as testing or planned may still change before release.',
        ],
        'nl' => [
            'title'=>'Light of Pro na de proefperiode',
            'intro'=>'Geschikte nieuwe gebruikers starten met 30 dagen de volledige Pro-ervaring. Daarna ga je verder met Light of kies je Pro. Het starten van de proefperiode leidt niet tot een automatische betaling.',
            'light_kicker'=>'GRATIS VERDER',
            'light_title'=>'NenoTV Light',
            'light'=>[
                'Blijf na de proefperiode dezelfde NenoTV-app gebruiken.',
                'Ga verder met de essentiële spelerervaring voor je eigen compatibele bron.',
                'Behoud lokale instellingen, favorieten, geschiedenis en compatibele kijkvoortgang waar app en apparaat dit ondersteunen.',
                'Upgrade later naar Pro zonder de app opnieuw te installeren.',
            ],
            'pro_kicker'=>'UITGEBREIDE ERVARING',
            'pro_title'=>'NenoTV Pro',
            'pro'=>[
                'Je gekozen taal krijgt voorrang in de spelerervaring.',
                'De EPG is bedoeld om je gekozen taal voorrang te geven wanneer de bron geschikte gidsdata aanlevert.',
                'Opname- en catch-upbediening waar bron, platform, opslag en rechten dit ondersteunen.',
                'Uitgebreidere bibliotheekorganisatie en geavanceerde kijkbediening.',
            ],
            'note'=>'Bekijk de pagina’s met ondersteunde apparaten en downloadstatus voor de actuele platformbeschikbaarheid. Functies die als test of gepland zijn aangeduid kunnen vóór release nog wijzigen.',
        ],
        'de' => [
            'title'=>'Light oder Pro nach der Testphase',
            'intro'=>'Berechtigte neue Nutzer starten mit 30 Tagen vollständiger Pro-Erfahrung. Danach läuft NenoTV als Light weiter oder du wählst Pro. Der Start der Testphase löst keine automatische Zahlung aus.',
            'light_kicker'=>'KOSTENLOS WEITER',
            'light_title'=>'NenoTV Light',
            'light'=>[
                'Nutze nach der Testphase dieselbe NenoTV-App weiter.',
                'Nutze die grundlegende Player-Erfahrung mit deiner eigenen kompatiblen Quelle weiter.',
                'Behalte lokale Einstellungen, Favoriten, Verlauf und kompatiblen Wiedergabefortschritt, soweit App und Gerät dies unterstützen.',
                'Wechsle später zu Pro, ohne die App neu zu installieren.',
            ],
            'pro_kicker'=>'ERWEITERTE ERFAHRUNG',
            'pro_title'=>'NenoTV Pro',
            'pro'=>[
                'Deine gewählte Sprache wird in der Player-Erfahrung priorisiert.',
                'Der EPG soll deine gewählte Sprache priorisieren, wenn die Quelle geeignete Guide-Daten bereitstellt.',
                'Aufnahme- und Catch-up-Steuerung, wenn Quelle, Plattform, Speicher und Berechtigungen dies unterstützen.',
                'Erweiterte Bibliotheksorganisation und zusätzliche Wiedergabesteuerung.',
            ],
            'note'=>'Aktuelle Plattformverfügbarkeit findest du auf den Seiten für unterstützte Geräte und Downloadstatus. Als Test oder geplant markierte Funktionen können sich vor der Veröffentlichung noch ändern.',
        ],
    ];
    $s = $copy[$lang] ?? $copy['en'];

    ob_start(); ?>
    <section class="nv-plan-compare" aria-labelledby="nv-plan-compare-title">
      <div class="nv-plan-compare-head">
        <h2 id="nv-plan-compare-title"><?php echo esc_html($s['title']); ?></h2>
        <p><?php echo esc_html($s['intro']); ?></p>
      </div>
      <div class="nv-plan-compare-grid">
        <article class="nv-plan-compare-card nv-plan-light">
          <span class="nv-plan-label"><?php echo esc_html($s['light_kicker']); ?></span>
          <h3><?php echo esc_html($s['light_title']); ?></h3>
          <ul><?php foreach ($s['light'] as $item) echo '<li>'.esc_html($item).'</li>'; ?></ul>
        </article>
        <article class="nv-plan-compare-card nv-plan-pro">
          <span class="nv-plan-label"><?php echo esc_html($s['pro_kicker']); ?></span>
          <h3><?php echo esc_html($s['pro_title']); ?></h3>
          <ul><?php foreach ($s['pro'] as $item) echo '<li>'.esc_html($item).'</li>'; ?></ul>
        </article>
      </div>
      <p class="nv-plan-compare-note"><?php echo esc_html($s['note']); ?></p>
    </section>
    <?php
    return (string)ob_get_clean();
}
add_shortcode('nenotv_light_pro_compare','nenotv_light_pro_compare_shortcode');

/**
 * Return a theme-owned visual for pages where the artwork is part of the
 * NenoTV interface rather than editorial media. Translations resolve through
 * their English canonical page so EN/NL/DE always use the same visual family.
 */
add_filter('do_shortcode_tag', static function ($output, $tag) {
    if ($tag !== 'nenotv_founding_testers') return $output;
    return str_replace('<form class="ntv-ft-form"', '<form id="nv-tester-application" class="ntv-ft-form"', $output);
}, 10, 2);

function nenotv_page_visual(int $post_id): array {
    if ($post_id <= 0) return [];

    $canonical_id = $post_id;
    if (function_exists('pll_get_post')) {
        $english_id = (int) pll_get_post($post_id, 'en');
        if ($english_id > 0) $canonical_id = $english_id;
    }

    $approved_artwork = [
        13   => [2548, 'NenoTV Help Center with setup, EPG, playback and account support'],
        575  => [2558, 'NenoTV Helpcentrum met installatie, EPG, afspelen en accountondersteuning'],
        582  => [2549, 'NenoTV Hilfe-Center mit Einrichtung, EPG, Wiedergabe und Kontounterstützung'],
        12   => [2554, 'NenoTV setup: choose source, add playlist, configure EPG and start playback'],
        574  => [2559, 'NenoTV installeren: bron kiezen, playlist toevoegen, EPG instellen en starten'],
        581  => [2555, 'NenoTV Einrichtung: Quelle wählen, Playlist hinzufügen, EPG einrichten und starten'],
        8    => [2556, 'NenoTV features: language priority, EPG, recording and smart library'],
        521  => [2560, 'NenoTV-functies: taalvoorkeur, EPG, opnemen en slimme bibliotheek'],
        547  => [2557, 'NenoTV Funktionen: Sprachpriorität, EPG, Aufnahme und smarte Bibliothek'],
        1278 => [2561, 'NenoTV Founding Testers: 25 Plätze und ein Jahr Pro nach der Veröffentlichung'],
        1276 => [2562, 'NenoTV Founding Testers: 25 places and one year of Pro after release'],
        1277 => [2563, 'NenoTV Founding Testers: 25 plaatsen en een jaar Pro na publicatie'],
    ];
    if (isset($approved_artwork[$post_id])) {
        [$attachment_id, $alt] = $approved_artwork[$post_id];
        $focus = trim((string)get_post_meta($post_id, '_yoast_wpseo_focuskw', true));
        if ($focus !== '' && mb_stripos((string)$alt, $focus, 0, 'UTF-8') === false) {
            $alt = $focus . ': ' . $alt;
        }
        return [
            'attachment_id' => $attachment_id,
            'url' => wp_get_attachment_image_url($attachment_id, 'large'),
            'alt' => $alt,
            'canonical_id' => $canonical_id,
            'approved_artwork' => true,
        ];
    }

    $map = [
        40 => ['buffering.webp', [
            'en'=>'NenoTV diagnostic illustration separating source, network, codec and device issues',
            'nl'=>'NenoTV-diagnoseillustratie die bron-, netwerk-, codec- en apparaatproblemen onderscheidt',
            'de'=>'NenoTV-Diagnoseillustration mit getrennten Quellen-, Netzwerk-, Codec- und Geräteproblemen',
        ]],
        45 => ['buffering.webp', [
            'en'=>'NenoTV buffering illustration showing source, network, codec and device as separate causes',
            'nl'=>'NenoTV-bufferingillustratie met bron, netwerk, codec en apparaat als afzonderlijke oorzaken',
            'de'=>'NenoTV-Pufferungsillustration mit Quelle, Netzwerk, Codec und Gerät als getrennten Ursachen',
        ]],
        8 => ['features-pro-hero.svg', [
            'en'=>'Pro features with language-first navigation, EPG, recording and library controls',
            'nl'=>'Pro-functies met taalvoorkeur, EPG, opnemen en uitgebreide bibliotheekbediening',
            'de'=>'Pro-Funktionen mit Sprachpriorität, EPG, Aufnahme und erweiterter Bibliothekssteuerung',
        ]],
        9 => ['light-pro-hero.svg', [
            'en'=>'NenoTV Light and NenoTV Pro comparison',
            'nl'=>'Vergelijking tussen NenoTV Light en NenoTV Pro',
            'de'=>'Vergleich zwischen NenoTV Light und NenoTV Pro',
        ]],
        278 => ['light-pro-hero.svg', [
            'en'=>'NenoTV Light and NenoTV Pro comparison',
            'nl'=>'Vergelijking tussen NenoTV Light en NenoTV Pro',
            'de'=>'Vergleich zwischen NenoTV Light und NenoTV Pro',
        ]],
        11 => ['devices-hero.svg', [
            'en'=>'NenoTV supported devices overview',
            'nl'=>'Overzicht van ondersteunde apparaten voor NenoTV',
            'de'=>'Übersicht der unterstützten Geräte für NenoTV',
        ]],
        12 => ['setup-hero.svg', [
            'en'=>'NenoTV setup screen for source and EPG configuration',
            'nl'=>'NenoTV-installatiescherm voor bron- en EPG-configuratie',
            'de'=>'NenoTV-Einrichtungsansicht für Quellen- und EPG-Konfiguration',
        ]],
        13 => ['help-hero.svg', [
            'en'=>'NenoTV Help Center for setup, EPG and playback troubleshooting',
            'nl'=>'NenoTV Helpcentrum voor installatie, EPG en afspeelproblemen',
            'de'=>'NenoTV Hilfe-Center für Einrichtung, EPG und Wiedergabeprobleme',
        ]],
        14 => ['whats-new-hero.svg', [
            'en'=>'NenoTV release notes and update timeline',
            'nl'=>'NenoTV release-notes en tijdlijn met updates',
            'de'=>'NenoTV Release Notes und Update-Zeitleiste',
        ]],
        16 => ['device-android-tv.svg', [
            'en'=>'NenoTV on Android TV and Google TV',
            'nl'=>'NenoTV op Android TV en Google TV',
            'de'=>'NenoTV auf Android TV und Google TV',
        ]],
        17 => ['device-mobile-tablet.svg', [
            'en'=>'NenoTV on Android phone and tablet',
            'nl'=>'NenoTV op Android-telefoon en tablet',
            'de'=>'NenoTV auf Android-Smartphone und Tablet',
        ]],
        18 => ['device-fire-tv.svg', ['en'=>'NenoTV for Fire TV','nl'=>'NenoTV voor Fire TV','de'=>'NenoTV für Fire TV']],
        19 => ['device-samsung-tv.svg', ['en'=>'NenoTV for Samsung TV','nl'=>'NenoTV voor Samsung TV','de'=>'NenoTV für Samsung TV']],
        20 => ['device-lg-webos.svg', ['en'=>'NenoTV for LG webOS','nl'=>'NenoTV voor LG webOS','de'=>'NenoTV für LG webOS']],
        21 => ['device-apple-tv.svg', ['en'=>'NenoTV for Apple TV','nl'=>'NenoTV voor Apple TV','de'=>'NenoTV für Apple TV']],
        22 => ['device-iphone-ipad.svg', ['en'=>'NenoTV for iPhone and iPad','nl'=>'NenoTV voor iPhone en iPad','de'=>'NenoTV für iPhone und iPad']],
        10 => ['download-hero.svg', [
            'en'=>'Official NenoTV download and controlled test release',
            'nl'=>'Officiële NenoTV-download en gecontroleerde testrelease',
            'de'=>'Offizieller NenoTV-Download und kontrollierte Testversion',
        ]],
        15 => ['support-hero.svg', [
            'en'=>'NenoTV support and contact help',
            'nl'=>'NenoTV Support en contacthulp',
            'de'=>'NenoTV Support und Kontakthilfe',
        ]],
        25 => ['portal-hero.svg', [
            'en'=>'Portal and Stalker source setup in NenoTV',
            'nl'=>'Portal- en Stalker-bron instellen in NenoTV',
            'de'=>'Portal- und Stalker-Quelle in NenoTV einrichten',
        ]],
        47 => ['payment-license-hero.svg', [
            'en'=>'NenoTV payment, licence and entitlement status',
            'nl'=>'NenoTV betaling, licentie en entitlementstatus',
            'de'=>'NenoTV Zahlung, Lizenz und Berechtigungsstatus',
        ]],
        51 => ['security-hero.svg', [
            'en'=>'NenoTV account and data security',
            'nl'=>'NenoTV account- en gegevensbeveiliging',
            'de'=>'NenoTV Konto- und Datensicherheit',
        ]],
        172 => ['refund-hero.svg', [
            'en'=>'NenoTV refund, withdrawal and Pro access reversal',
            'nl'=>'NenoTV terugbetaling, herroeping en intrekking van Pro-toegang',
            'de'=>'NenoTV Erstattung, Widerruf und Entzug des Pro-Zugangs',
        ]],
        281 => ['status-hero.svg', [
            'en'=>'NenoTV service and system status',
            'nl'=>'NenoTV dienst- en systeemstatus',
            'de'=>'NenoTV Dienst- und Systemstatus',
        ]],
        282 => ['connection-hero.svg', [
            'en'=>'Secure connection between NenoTV app and website account',
            'nl'=>'Veilige verbinding tussen NenoTV-app en websiteaccount',
            'de'=>'Sichere Verbindung zwischen NenoTV-App und Website-Konto',
        ]],
        594 => ['company-hero.svg', [
            'en'=>'NenoTV public company details',
            'nl'=>'Openbare NenoTV-bedrijfsgegevens',
            'de'=>'Öffentliche NenoTV-Unternehmensangaben',
        ]],
        1276 => ['testers-hero.svg', [
            'en'=>'NenoTV Android founding tester programme',
            'nl'=>'NenoTV Android Founding Tester-programma',
            'de'=>'NenoTV Android Founding-Tester-Programm',
        ]],
    ];
    if (!isset($map[$canonical_id])) return [];

    [$file, $descriptions] = $map[$canonical_id];
    $lang = function_exists('pll_get_post_language') ? strtolower((string)pll_get_post_language($post_id, 'slug')) : '';
    if (!in_array($lang, ['en','nl','de'], true)) $lang = function_exists('nenotv_current_language') ? nenotv_current_language() : 'en';
    $description = (string)($descriptions[$lang] ?? $descriptions['en']);
    $focus = trim((string)get_post_meta($post_id, '_yoast_wpseo_focuskw', true));
    $alt = $focus !== '' ? $focus . ': ' . $description : $description;

    return [
        'url' => trailingslashit(get_template_directory_uri()) . 'assets/visuals/' . $file,
        'alt' => $alt,
        'file' => $file,
        'canonical_id' => $canonical_id,
    ];
}

function nenotv_display_page_title($post_id = 0): string {
    $post_id = $post_id ? absint($post_id) : get_the_ID();
    $lang = nenotv_current_language();
    $shared = [
        169 => ['en'=>'Cart','nl'=>'Winkelwagen','de'=>'Warenkorb'],
        170 => ['en'=>'Checkout','nl'=>'Afrekenen','de'=>'Kasse'],
        171 => ['en'=>'My NenoTV','nl'=>'Mijn NenoTV','de'=>'Mein NenoTV'],
    ];
    if (isset($shared[$post_id])) return $shared[$post_id][$lang] ?? $shared[$post_id]['en'];
    return get_the_title($post_id);
}

function nenotv_breadcrumbs() {
    if (is_front_page()) return;
    echo '<div class="nv-breadcrumbs"><a href="'.esc_url(nenotv_home_url()).'">NenoTV</a>';
    if (is_page()) {
        global $post;
        $parents = array_reverse(get_post_ancestors($post));
        foreach ($parents as $parent_id) {
            $parent_post = get_post($parent_id);
            echo ' <span>›</span> <a href="'.esc_url(nenotv_post_view_url($parent_post)).'">'.esc_html(get_the_title($parent_id)).'</a>';
        }
        echo ' <span>›</span> <span>'.esc_html(nenotv_display_page_title(get_the_ID())).'</span>';
    }
    echo '</div>';
}

function nenotv_status_for_slug($slug) {
    $map = [
        'android-tv-google-tv' => 'In development',
        'android-mobile-tablet' => 'Active testing',
        'lg-webos' => 'In development',
        'fire-tv' => 'Planned',
        'samsung-tv' => 'Planned',
        'apple-tv' => 'Planned',
        'iphone-ipad' => 'Planned',
        'recording-catch-up' => 'Planned',
        'multiview' => 'Planned',
        'parental-controls' => 'Planned',
        'cloud-backup' => 'Planned',
        'source-health' => 'Planned',
        'qr-pairing' => 'Integration prepared',
        'device-sync' => 'Planned',
        'migrate-restore' => 'Planned',
        'error-codes' => 'Concept',
        'compatibility-matrix' => 'Concept',
    ];
    $status = $map[$slug] ?? '';
    $lang = function_exists('nenotv_current_language') ? nenotv_current_language() : 'en';
    $labels = [
        'nl' => ['In development'=>'In ontwikkeling', 'Active testing'=>'Actieve testfase', 'Planned'=>'Gepland', 'Concept'=>'Concept', 'Integration prepared'=>'Koppeling voorbereid'],
        'de' => ['In development'=>'In Entwicklung', 'Active testing'=>'Aktive Testphase', 'Planned'=>'Geplant', 'Concept'=>'Konzept', 'Integration prepared'=>'Kopplung vorbereitet'],
    ];
    return $labels[$lang][$status] ?? $status;
}

function nenotv_child_cards($parent_id) {
    $children = get_pages([
        'parent' => (int)$parent_id,
        'sort_column' => 'menu_order,post_title',
        'post_status' => current_user_can('edit_pages') ? ['draft','publish'] : ['publish'],
    ]);
    if (!$children) return;

    usort($children, static function ($a, $b) {
        $canonical_a = $a;
        $canonical_b = $b;

        if (function_exists('pll_get_post')) {
            $a_en = (int) pll_get_post($a->ID, 'en');
            $b_en = (int) pll_get_post($b->ID, 'en');
            if ($a_en > 0) {
                $translated = get_post($a_en);
                if ($translated) $canonical_a = $translated;
            }
            if ($b_en > 0) {
                $translated = get_post($b_en);
                if ($translated) $canonical_b = $translated;
            }
        }

        $menu_compare = ((int)$canonical_a->menu_order) <=> ((int)$canonical_b->menu_order);
        if ($menu_compare !== 0) return $menu_compare;

        return strcasecmp((string)$canonical_a->post_title, (string)$canonical_b->post_title);
    });

    echo '<div class="nv-child-grid">';
    foreach ($children as $child) {
        $thumb_id = get_post_thumbnail_id($child->ID);
        $custom_visual = function_exists('nenotv_page_visual') ? nenotv_page_visual((int)$child->ID) : [];
        $status_slug = $child->post_name;
        if (function_exists('pll_get_post')) {
            $child_en = (int) pll_get_post($child->ID, 'en');
            if ($child_en > 0) {
                $english_slug = (string) get_post_field('post_name', $child_en);
                if ($english_slug !== '') $status_slug = $english_slug;
            }
        }
        $status = nenotv_status_for_slug($status_slug);
        echo '<a class="nv-child-card" href="'.esc_url(nenotv_post_view_url($child)).'">';
        echo '<div class="nv-child-thumb">';
        if (!empty($custom_visual)) {
            echo '<img class="nv-child-image" src="'.esc_url((string)$custom_visual['url']).'" alt="'.esc_attr((string)$custom_visual['alt']).'" loading="lazy" decoding="async">';
        } elseif ($thumb_id) {
            echo wp_get_attachment_image($thumb_id, 'medium_large', false, ['class' => 'nv-child-image', 'data-nenotv-context-post' => (int)$child->ID]);
        }
        echo '</div><div class="nv-child-body"><strong>'.esc_html($child->post_title).'</strong>';
        if ($child->post_excerpt) echo '<span>'.esc_html(wp_trim_words(wp_strip_all_tags($child->post_excerpt),18,'…')).'</span>';
        if ($status) echo '<span class="nv-status">'.esc_html($status).'</span>';
        echo '</div></a>';
    }
    echo '</div>';
}

function nenotv_contact_email() {
    return 'info@nenotv.com';
}


function nenotv_brand_icon_links() {
    $base = get_template_directory_uri() . '/assets/brand/';
    echo "\n<link rel=\"icon\" href=\"" . esc_url($base . 'favicon.ico') . "\" sizes=\"any\">\n";
    echo "<link rel=\"icon\" type=\"image/svg+xml\" href=\"" . esc_url($base . 'nenotv-app-icon.svg') . "\">\n";
    echo "<link rel=\"icon\" type=\"image/png\" sizes=\"32x32\" href=\"" . esc_url($base . 'nenotv-icon-32.png') . "\">\n";
    echo "<link rel=\"icon\" type=\"image/png\" sizes=\"192x192\" href=\"" . esc_url($base . 'nenotv-icon-192.png') . "\">\n";
    echo "<link rel=\"apple-touch-icon\" sizes=\"180x180\" href=\"" . esc_url($base . 'nenotv-icon-180.png') . "\">\n";
    echo "<meta name=\"theme-color\" content=\"#0A0A0A\">\n";
}
add_action('wp_head', 'nenotv_brand_icon_links', 1);
add_action('admin_head', 'nenotv_brand_icon_links', 1);
add_action('login_head', 'nenotv_brand_icon_links', 1);

function nenotv_login_logo() {
    $logo = get_template_directory_uri() . '/assets/brand/nenotv-mark.svg';
    echo '<style>body.login h1 a{background-image:url(' . esc_url($logo) . ');background-size:84px 84px;width:84px;height:84px}</style>';
}
add_action('login_head', 'nenotv_login_logo');


function nenotv_free_version() {
    $version = trim((string)get_option('nenotv_free_stable_version', '0.12.6.5'));
    return $version !== '' ? $version : '0.12.6.5';
}

function nenotv_app_api_operational() {
    $health = get_option('nenotv_app_monitor_last_health', []);
    return is_array($health) && !empty($health['ok']);
}

function nenotv_localized_woocommerce_page_id($page_id) {
    $page_id = absint($page_id);
    if ($page_id <= 0 || !function_exists('pll_get_post')) return $page_id;
    if (is_admin() && !wp_doing_ajax()) return $page_id;

    $lang = nenotv_current_language();
    if ($lang === 'en' || !in_array($lang, nenotv_supported_language_slugs(), true)) return $page_id;

    $translated_id = (int) pll_get_post($page_id, $lang);
    if ($translated_id > 0 && get_post_status($translated_id) === 'publish') {
        return $translated_id;
    }
    return $page_id;
}
add_filter('woocommerce_get_cart_page_id', 'nenotv_localized_woocommerce_page_id', 20);
add_filter('woocommerce_get_checkout_page_id', 'nenotv_localized_woocommerce_page_id', 20);
add_filter('woocommerce_get_myaccount_page_id', 'nenotv_localized_woocommerce_page_id', 20);

function nenotv_public_page_url($slug, $fallback = '/') {
    $slug = trim($slug, '/');
    $lang = nenotv_current_language();

    if (in_array($slug, ['my-account', 'cart', 'checkout'], true) && function_exists('wc_get_page_id')) {
        $commerce_key = $slug === 'my-account' ? 'myaccount' : $slug;
        $commerce_id = (int) wc_get_page_id($commerce_key);
        $commerce_page = $commerce_id > 0 ? get_post($commerce_id) : null;
        if ($commerce_page instanceof WP_Post && $commerce_page->post_status === 'publish') {
            return get_permalink($commerce_page);
        }
    }

    $localized_slugs = [
        'nl' => [
            'supported-devices' => 'ondersteunde-apparaten',
            'setup' => 'installatie',
            'help' => 'helpcentrum',
            'download' => 'nenotv-downloaden',
            'contact' => 'contact-nl',
            'pricing' => 'prijzen',
            'pro' => 'nenotv-pro-nl',
        ],
        'de' => [
            'supported-devices' => 'unterstuetzte-geraete',
            'setup' => 'einrichtung',
            'help' => 'hilfe',
            'download' => 'nenotv-herunterladen',
            'contact' => 'kontakt',
            'pricing' => 'preise',
            'pro' => 'nenotv-pro-de',
        ],
    ];

    if ($lang !== 'en' && isset($localized_slugs[$lang][$slug])) {
        $localized_page = get_page_by_path($localized_slugs[$lang][$slug]);
        if ($localized_page instanceof WP_Post && $localized_page->post_status === 'publish') {
            return get_permalink($localized_page);
        }
    }

    $page = get_page_by_path($slug);

    if ($page instanceof WP_Post && function_exists('pll_get_post')) {
        $translated_id = pll_get_post($page->ID, $lang);
        if ($translated_id && ($lang === 'en' || (int)$translated_id !== (int)$page->ID)) {
            $page = get_post($translated_id);
        } elseif ($lang !== 'en') {
            $english_fallbacks = [
                'privacy',
                'terms',
                'refund-policy',
                'security',
                'legal-content-disclaimer',
                'whats-new',
            ];
            if (in_array($slug, $english_fallbacks, true) && $page->post_status === 'publish') {
                return get_permalink($page);
            }

            $anchors = [
                'features' => '#features',
                'supported-devices' => '#platforms',
                'setup' => '#features',
                'help' => '#support',
                'whats-new' => '',
                'download' => '#platforms',
                'contact' => '#support',
                'my-account' => '',
                'cart' => '',
                'pricing' => '',
                'pro' => '',
            ];
            return nenotv_home_url() . ($anchors[$slug] ?? '');
        }
    }

    if ($page instanceof WP_Post) {
        if ($page->post_status === 'publish') {
            return get_permalink($page);
        }
        if (current_user_can('edit_post', $page->ID)) {
            $preview = get_preview_post_link($page);
            if ($preview) return $preview;
        }
    }

    return $lang !== 'en' ? nenotv_home_url() : home_url($fallback);
}





function nenotv_store_order_language($order) {
    if (!$order || !method_exists($order, 'update_meta_data')) return;
    $lang = nenotv_current_language();
    if (in_array($lang, nenotv_supported_language_slugs(), true)) {
        $order->update_meta_data('_nenotv_order_language', $lang);
    }
}
add_action('woocommerce_checkout_create_order', 'nenotv_store_order_language', 20, 1);
add_action('woocommerce_store_api_checkout_update_order_from_request', 'nenotv_store_order_language', 20, 1);

function nenotv_store_checkout_language_session(): void {
    if (!function_exists('WC') || !WC()->session || !function_exists('is_checkout') || !is_checkout()) return;
    $lang = nenotv_current_language();
    if (in_array($lang, nenotv_supported_language_slugs(), true)) {
        WC()->session->set('nenotv_checkout_language', $lang);
    }
}
add_action('template_redirect', 'nenotv_store_checkout_language_session', 8);

function nenotv_localize_order_received_url($url, $order) {
    if (!$order || !method_exists($order, 'get_meta')) return $url;
    $lang = (string)$order->get_meta('_nenotv_order_language', true);
    if ($lang !== 'en' && in_array($lang, nenotv_supported_language_slugs(), true)) {
        return add_query_arg('lang', $lang, $url);
    }
    return $url;
}
add_filter('woocommerce_get_checkout_order_received_url', 'nenotv_localize_order_received_url', 20, 2);



function nenotv_commerce_strings(): array {
    $lang = nenotv_current_language();
    $all = [
        'en' => [
            'cart_kicker' => 'NenoTV Pro checkout',
            'cart_title' => 'Your order, clearly summarized',
            'cart_text' => 'Review the NenoTV Pro option shown at checkout before continuing. A purchase during the 30-day Pro trial keeps Pro after the trial; a later purchase upgrades Light to Pro in the same app. Payment and activation are handled only after you place a paid order.',
            'checkout_kicker' => 'Secure checkout',
            'checkout_title' => 'Almost done',
            'checkout_text' => 'Enter your details and pay with iDEAL, credit card or PayPal. Your NenoTV account is created during checkout and Pro is activated automatically after successful payment. The 30-day Pro trial never creates a paid order automatically.',
            'step1' => 'Choose Pro',
            'step2' => 'Pay securely',
            'step3' => 'Automatic activation',
            'trust1' => 'iDEAL, credit card or PayPal via Mollie',
            'trust2' => 'Automatic order processing',
            'trust3' => 'Support ticket if something goes wrong',
            'product_yearly' => 'One year of eligible NenoTV Pro features from successful payment. No automatic renewal.',
            'product_lifetime' => 'One-time purchase for eligible NenoTV Pro features.',
            'product_auto' => 'Automatic activation after successful payment',
            'product_secure' => 'Secure checkout',
            'product_support' => 'Ticket support when needed',
            'launch' => 'Launch offer',
            'yearly' => 'Yearly plan',
            'thank_title' => 'Payment received — NenoTV is processing your order',
            'thank_text' => 'Your account, order and Pro access are handled automatically. You only need support if something does not complete correctly.',
        ],
        'nl' => [
            'cart_kicker' => 'NenoTV Pro bestellen',
            'cart_title' => 'Je bestelling helder op een rij',
            'cart_text' => 'Controleer de NenoTV Pro-optie voordat je doorgaat. Een aankoop tijdens de Pro-proefperiode van 30 dagen behoudt Pro daarna; een latere aankoop upgrade Light naar Pro in dezelfde app. Betaling en activatie worden alleen na een door jou geplaatste betaalde bestelling verwerkt.',
            'checkout_kicker' => 'Veilig afrekenen',
            'checkout_title' => 'Bijna klaar',
            'checkout_text' => 'Vul je gegevens in en betaal met iDEAL, creditcard of PayPal. Je NenoTV-account wordt tijdens het afrekenen aangemaakt en Pro wordt na succesvolle betaling automatisch geactiveerd. De Pro-proefperiode van 30 dagen maakt nooit automatisch een betaalde bestelling.',
            'step1' => 'Kies Pro',
            'step2' => 'Betaal veilig',
            'step3' => 'Automatische activatie',
            'trust1' => 'iDEAL, creditcard of PayPal via Mollie',
            'trust2' => 'Automatische orderverwerking',
            'trust3' => 'Supportticket als er iets misgaat',
            'product_yearly' => 'Eén jaar NenoTV Pro-functies vanaf succesvolle betaling. Geen automatische verlenging.',
            'product_lifetime' => 'Eenmalige aankoop voor NenoTV Pro-functies.',
            'product_auto' => 'Automatische activatie na succesvolle betaling',
            'product_secure' => 'Veilig afrekenen',
            'product_support' => 'Ticketsupport wanneer nodig',
            'launch' => 'Launchaanbieding',
            'yearly' => 'Jaarplan',
            'thank_title' => 'Betaling ontvangen — NenoTV verwerkt je bestelling',
            'thank_text' => 'Je account, bestelling en Pro-toegang worden automatisch verwerkt. Alleen als iets niet goed afrondt, heb je support nodig.',
        ],
        'de' => [
            'cart_kicker' => 'NenoTV Pro bestellen',
            'cart_title' => 'Deine Bestellung klar zusammengefasst',
            'cart_text' => 'Prüfe die NenoTV-Pro-Option, bevor du fortfährst. Ein Kauf während der 30-tägigen Pro-Testphase behält Pro danach; ein späterer Kauf aktualisiert Light in derselben App auf Pro. Zahlung und Aktivierung erfolgen nur nach einer von dir aufgegebenen kostenpflichtigen Bestellung.',
            'checkout_kicker' => 'Sicher bezahlen',
            'checkout_title' => 'Fast geschafft',
            'checkout_text' => 'Gib deine Daten ein und bezahle mit iDEAL, Kreditkarte oder PayPal. Dein NenoTV-Konto wird beim Checkout erstellt und Pro nach erfolgreicher Zahlung automatisch aktiviert. Die 30-tägige Pro-Testphase erstellt niemals automatisch eine kostenpflichtige Bestellung.',
            'step1' => 'Pro wählen',
            'step2' => 'Sicher bezahlen',
            'step3' => 'Automatische Aktivierung',
            'trust1' => 'iDEAL, Kreditkarte oder PayPal über Mollie',
            'trust2' => 'Automatische Bestellverarbeitung',
            'trust3' => 'Support-Ticket bei Problemen',
            'product_yearly' => 'Ein Jahr NenoTV Pro-Funktionen ab erfolgreicher Zahlung. Keine automatische Verlängerung.',
            'product_lifetime' => 'Einmaliger Kauf für NenoTV Pro-Funktionen.',
            'product_auto' => 'Automatische Aktivierung nach erfolgreicher Zahlung',
            'product_secure' => 'Sicherer Checkout',
            'product_support' => 'Ticket-Support bei Bedarf',
            'launch' => 'Launch-Angebot',
            'yearly' => 'Jahresplan',
            'thank_title' => 'Zahlung erhalten — NenoTV verarbeitet deine Bestellung',
            'thank_text' => 'Konto, Bestellung und Pro-Zugriff werden automatisch verarbeitet. Support ist nur nötig, wenn etwas nicht korrekt abgeschlossen wird.',
        ],
    ];
    return $all[$lang] ?? $all['en'];
}

function nenotv_commerce_progress(array $s): string {
    return '<div class="nv-commerce-progress" aria-label="' . esc_attr($s['checkout_kicker']) . '">'
        . '<span><b>1</b>' . esc_html($s['step1']) . '</span>'
        . '<i aria-hidden="true"></i>'
        . '<span><b>2</b>' . esc_html($s['step2']) . '</span>'
        . '<i aria-hidden="true"></i>'
        . '<span><b>3</b>' . esc_html($s['step3']) . '</span>'
        . '</div>';
}

function nenotv_commerce_trust(array $s): string {
    return '<div class="nv-commerce-trust">'
        . '<span><strong>✓</strong>' . esc_html($s['trust1']) . '</span>'
        . '<span><strong>✓</strong>' . esc_html($s['trust2']) . '</span>'
        . '<span><strong>✓</strong>' . esc_html($s['trust3']) . '</span>'
        . '</div>';
}

function nenotv_cart_intro_shortcode(): string {
    $s = nenotv_commerce_strings();
    if (!nenotv_public_sales_ready()) {
        $lang = nenotv_current_language();
        $kicker = $lang === 'nl' ? 'NenoTV · Light & Pro' : ($lang === 'de' ? 'NenoTV · Light & Pro' : 'NenoTV · Light & Pro');
        $title = $lang === 'nl' ? 'NenoTV Pro is nog niet te koop' : ($lang === 'de' ? 'NenoTV Pro ist noch nicht erhältlich' : 'NenoTV Pro is not on sale yet');
        $text = $lang === 'nl'
            ? 'Bij de publieke release krijgen nieuwe gebruikers eerst 30 dagen de volledige Pro-ervaring. Koop je daarna geen Pro, dan blijft NenoTV werken als Light. Er wordt niets automatisch afgeschreven. Je kunt de prijzen al bekijken, maar bestellen kan nog niet.'
            : ($lang === 'de'
                ? 'Mit der öffentlichen Veröffentlichung erhalten neue Nutzer zuerst 30 Tage die vollständige Pro-Erfahrung. Wird danach kein Pro gekauft, läuft NenoTV als Light weiter. Es wird nichts automatisch abgebucht. Die Preise sind bereits sichtbar, bestellen ist aber noch nicht möglich.'
                : 'At public release, new users will first receive 30 days of the complete Pro experience. If Pro is not purchased, NenoTV continues as Light. Nothing is charged automatically. You can already view the prices, but ordering is not available yet.');
        return '<section class="nv-commerce-intro"><div class="nv-commerce-intro-copy"><div class="nv-commerce-kicker">' . esc_html($kicker) . '</div><h2>' . esc_html($title) . '</h2><p>' . esc_html($text) . '</p></div><div class="nv-commerce-mark"><img src="' . esc_url(get_template_directory_uri() . '/assets/brand/nenotv-mark.svg') . '" width="108" height="108" alt=""></div></section>';
    }
    return '<section class="nv-commerce-intro">'
        . '<div class="nv-commerce-intro-copy"><div class="nv-commerce-kicker">' . esc_html($s['cart_kicker']) . '</div>'
        . '<h2>' . esc_html($s['cart_title']) . '</h2><p>' . esc_html($s['cart_text']) . '</p></div>'
        . '<div class="nv-commerce-mark"><img src="' . esc_url(get_template_directory_uri() . '/assets/brand/nenotv-mark.svg') . '" width="108" height="108" alt=""></div>'
        . nenotv_commerce_progress($s)
        . nenotv_commerce_trust($s)
        . '</section>';
}
add_shortcode('nenotv_cart_intro', 'nenotv_cart_intro_shortcode');

function nenotv_checkout_intro_shortcode(): string {
    $s = nenotv_commerce_strings();
    if (!nenotv_public_sales_ready()) {
        $lang = nenotv_current_language();
        $kicker = 'NenoTV · Pro';
        $title = $lang === 'nl' ? 'Afrekenen is nog niet beschikbaar' : ($lang === 'de' ? 'Der Checkout ist noch nicht verfügbar' : 'Checkout is not available yet');
        $text = $lang === 'nl'
            ? 'Je kunt NenoTV Pro nu nog niet kopen. Zodra de openbare verkoop start, kun je hier veilig afrekenen. Nieuwe gebruikers krijgen bij de publieke release eerst 30 dagen Pro; zonder aankoop gaat NenoTV daarna verder als Light. Er is geen automatische betaling.'
            : ($lang === 'de'
                ? 'NenoTV Pro kann derzeit noch nicht gekauft werden. Sobald der öffentliche Verkauf startet, kannst du hier sicher bezahlen. Neue Nutzer erhalten bei der öffentlichen Veröffentlichung zuerst 30 Tage Pro; ohne Kauf läuft NenoTV danach als Light weiter. Es gibt keine automatische Zahlung.'
                : 'You cannot buy NenoTV Pro yet. When public sales open, you will be able to check out here. New users receive 30 days of Pro at public release; without a purchase, NenoTV then continues as Light. There is no automatic payment.');
        return '<section class="nv-commerce-intro nv-commerce-intro-checkout"><div class="nv-commerce-intro-copy"><div class="nv-commerce-kicker">' . esc_html($kicker) . '</div><h2>' . esc_html($title) . '</h2><p>' . esc_html($text) . '</p></div><div class="nv-commerce-mark"><img src="' . esc_url(get_template_directory_uri() . '/assets/brand/nenotv-mark.svg') . '" width="108" height="108" alt=""></div></section>';
    }
    return '<section class="nv-commerce-intro nv-commerce-intro-checkout">'
        . '<div class="nv-commerce-intro-copy"><div class="nv-commerce-kicker">' . esc_html($s['checkout_kicker']) . '</div>'
        . '<h2>' . esc_html($s['checkout_title']) . '</h2><p>' . esc_html($s['checkout_text']) . '</p></div>'
        . '<div class="nv-commerce-mark"><img src="' . esc_url(get_template_directory_uri() . '/assets/brand/nenotv-mark.svg') . '" width="108" height="108" alt=""></div>'
        . nenotv_commerce_progress($s)
        . nenotv_commerce_trust($s)
        . '</section>';
}
add_shortcode('nenotv_checkout_intro', 'nenotv_checkout_intro_shortcode');

function nenotv_payment_gateway_title($title, $gateway_id): string {
    $id = (string)$gateway_id;
    if ($id === 'mollie_wc_gateway_creditcard') {
        $lang = nenotv_current_language();
        return $lang === 'nl' ? 'Creditcard' : ($lang === 'de' ? 'Kreditkarte' : 'Credit card');
    }
    if ($id === 'mollie_wc_gateway_ideal') return 'iDEAL';
    if ($id === 'mollie_wc_gateway_paypal') return 'PayPal';
    return (string)$title;
}
add_filter('woocommerce_gateway_title', 'nenotv_payment_gateway_title', 20, 2);

function nenotv_payment_gateway_description($description, $gateway_id): string {
    $id = (string)$gateway_id;
    if (!in_array($id, ['mollie_wc_gateway_creditcard','mollie_wc_gateway_ideal','mollie_wc_gateway_paypal'], true)) {
        return (string)$description;
    }
    $lang = nenotv_current_language();
    if ($lang === 'nl') {
        if ($id === 'mollie_wc_gateway_creditcard') return 'Betaal veilig met je creditcard via Mollie.';
        if ($id === 'mollie_wc_gateway_ideal') return 'Betaal veilig via iDEAL met je eigen bank.';
        return 'Betaal veilig met PayPal.';
    }
    if ($lang === 'de') {
        if ($id === 'mollie_wc_gateway_creditcard') return 'Sicher mit Kreditkarte über Mollie bezahlen.';
        if ($id === 'mollie_wc_gateway_ideal') return 'Sicher mit iDEAL über deine Bank bezahlen.';
        return 'Sicher mit PayPal bezahlen.';
    }
    if ($id === 'mollie_wc_gateway_creditcard') return 'Pay securely by credit card via Mollie.';
    if ($id === 'mollie_wc_gateway_ideal') return 'Pay securely with iDEAL through your bank.';
    return 'Pay securely with PayPal.';
}
add_filter('woocommerce_gateway_description', 'nenotv_payment_gateway_description', 20, 2);

function nenotv_localized_legal_page_id($page_id): int {
    $page_id = absint($page_id);
    if (!$page_id || !function_exists('pll_get_post')) return $page_id;
    $lang = nenotv_current_language();
    $translated = absint(pll_get_post($page_id, $lang));
    return $translated > 0 ? $translated : $page_id;
}
add_filter('woocommerce_terms_and_conditions_page_id', 'nenotv_localized_legal_page_id', 20, 1);
add_filter('woocommerce_privacy_policy_page_id', 'nenotv_localized_legal_page_id', 20, 1);

function nenotv_set_new_customer_language($customer_id, $new_customer_data = [], $password_generated = false): void {
    $customer_id = absint($customer_id);
    if (!$customer_id) return;
    $lang = nenotv_current_language();
    $locale_map = ['en'=>'en_GB','nl'=>'nl_NL','de'=>'de_DE'];
    $locale = $locale_map[$lang] ?? 'en_GB';
    update_user_meta($customer_id, 'locale', $locale);
    update_user_meta($customer_id, 'nenotv_language', $lang);
}
add_action('woocommerce_created_customer', 'nenotv_set_new_customer_language', 5, 3);

function nenotv_email_user_language($object = null): string {
    if ($object instanceof WP_User) {
        $locale = strtolower((string)get_user_locale($object));
        if (str_starts_with($locale, 'nl')) return 'nl';
        if (str_starts_with($locale, 'de')) return 'de';
        if (str_starts_with($locale, 'en')) return 'en';
    }
    return nenotv_current_language();
}

function nenotv_new_account_email_subject($subject, $object, $email): string {
    $lang = nenotv_email_user_language($object);
    return $lang === 'nl'
        ? 'Je NenoTV-account is aangemaakt'
        : ($lang === 'de' ? 'Dein NenoTV-Konto wurde erstellt' : 'Your NenoTV account has been created');
}
add_filter('woocommerce_email_subject_customer_new_account', 'nenotv_new_account_email_subject', 20, 3);

function nenotv_new_account_email_heading($heading, $object, $email): string {
    $lang = nenotv_email_user_language($object);
    return $lang === 'nl'
        ? 'Welkom bij NenoTV'
        : ($lang === 'de' ? 'Willkommen bei NenoTV' : 'Welcome to NenoTV');
}
add_filter('woocommerce_email_heading_customer_new_account', 'nenotv_new_account_email_heading', 20, 3);

function nenotv_new_account_email_additional_content($content, $object, $email): string {
    $lang = nenotv_email_user_language($object);
    if ($lang === 'nl') {
        return 'Via Mijn NenoTV beheer je je Pro-toegang, gekoppelde apparaten, bestellingen en facturen. Deel je wachtwoord of private playlistlinks nooit met support.';
    }
    if ($lang === 'de') {
        return 'In Mein NenoTV verwaltest du deinen Pro-Zugang, verbundene Geräte, Bestellungen und Rechnungen. Teile dein Passwort oder private Playlist-Links niemals mit dem Support.';
    }
    return 'In My NenoTV you can manage Pro access, linked devices, orders and invoices. Never share your password or private playlist links with support.';
}
add_filter('woocommerce_email_additional_content_customer_new_account', 'nenotv_new_account_email_additional_content', 20, 3);

function nenotv_product_placeholder_image(string $src): string {
    $product = null;

    if (isset($GLOBALS['product']) && $GLOBALS['product'] instanceof WC_Product) {
        $product = $GLOBALS['product'];
    } elseif (function_exists('wc_get_product')) {
        $post_id = get_the_ID();
        if ($post_id && get_post_type($post_id) === 'product') {
            $product = wc_get_product($post_id);
        }
    }

    if ($product instanceof WC_Product) {
        $sku = strtoupper((string)$product->get_sku());
        $map = [
            'NENOTV-PRO-1-YEARLY'   => 'solo-yearly',
            'NENOTV-PRO-1-LIFETIME' => 'solo-lifetime',
            'NENOTV-PRO-5-YEARLY'   => 'multi-yearly',
            'NENOTV-PRO-5-LIFETIME' => 'multi-lifetime',
        ];
        if (isset($map[$sku])) {
            if (is_admin()) {
                $locale = strtolower((string)get_user_locale());
                $lang = str_starts_with($locale, 'nl') ? 'nl' : (str_starts_with($locale, 'de') ? 'de' : 'en');
            } else {
                $lang = function_exists('nenotv_current_language') ? nenotv_current_language() : 'en';
            }
            return get_template_directory_uri() . '/assets/brand/pro-' . $map[$sku] . '-' . $lang . '.svg';
        }
    }

    return get_template_directory_uri() . '/assets/brand/nenotv-mark.svg';
}
add_filter('woocommerce_placeholder_img_src', 'nenotv_product_placeholder_image');

function nenotv_product_card_asset($product, ?string $lang = null): string {
    if (!$product instanceof WC_Product) return '';
    $sku = strtoupper((string)$product->get_sku());
    $map = [
        'NENOTV-PRO-1-YEARLY'   => 'solo-yearly',
        'NENOTV-PRO-1-LIFETIME' => 'solo-lifetime',
        'NENOTV-PRO-5-YEARLY'   => 'multi-yearly',
        'NENOTV-PRO-5-LIFETIME' => 'multi-lifetime',
    ];
    if (!isset($map[$sku])) return '';
    if ($lang === null) {
        if (is_admin()) {
            $locale = strtolower((string)get_user_locale());
            $lang = str_starts_with($locale, 'nl') ? 'nl' : (str_starts_with($locale, 'de') ? 'de' : 'en');
        } else {
            $lang = nenotv_current_language();
        }
    }
    if (!in_array($lang, ['en','nl','de'], true)) $lang = 'en';
    return get_template_directory_uri() . '/assets/brand/pro-' . $map[$sku] . '-' . $lang . '.svg';
}

function nenotv_product_card_image_html($image, $product, $size, $attr, $placeholder): string {
    if (!$product instanceof WC_Product || $product->get_image_id()) return (string)$image;
    $src = nenotv_product_card_asset($product);
    if ($src === '') return (string)$image;
    $alt = $product->get_name();
    return '<img src="' . esc_url($src) . '" alt="' . esc_attr($alt) . '" class="woocommerce-placeholder wp-post-image" loading="lazy">';
}
add_filter('woocommerce_product_get_image', 'nenotv_product_card_image_html', 20, 5);

function nenotv_store_api_cart_item_images($images, $cart_item, $cart_item_key): array {
    $product = is_array($cart_item) && isset($cart_item['data']) && $cart_item['data'] instanceof WC_Product
        ? $cart_item['data']
        : null;
    if (!$product instanceof WC_Product) return is_array($images) ? $images : [];

    $src = nenotv_product_card_asset($product);
    if ($src === '') return is_array($images) ? $images : [];

    $name = $product->get_name();
    return [(object)[
        'id' => (int)$product->get_id(),
        'src' => $src,
        'thumbnail' => $src,
        'srcset' => '',
        'sizes' => '',
        'thumbnail_srcset' => '',
        'thumbnail_sizes' => '',
        'name' => $name,
        'alt' => $name,
    ]];
}
add_filter('woocommerce_store_api_cart_item_images', 'nenotv_store_api_cart_item_images', 20, 3);

function nenotv_product_plan_panel(): void {
    global $product;
    if (!$product instanceof WC_Product) return;
    $sku = strtoupper((string)$product->get_sku());
    if (!str_starts_with($sku, 'NENOTV-PRO')) return;

    $s = nenotv_commerce_strings();
    $is_lifetime = str_contains($sku, 'LIFETIME');
    $is_solo = str_contains($sku, 'PRO-1-');
    $is_multi = str_contains($sku, 'PRO-5-') || in_array($sku, ['NENOTV-PRO-YEARLY','NENOTV-PRO-LIFETIME'], true);
    $text = $is_lifetime ? $s['product_lifetime'] : $s['product_yearly'];
    $badge = $is_lifetime && $is_multi ? $s['launch'] : ($is_lifetime ? 'Lifetime' : $s['yearly']);
    $lang = nenotv_current_language();
    $device_text = $is_solo
        ? ($lang === 'nl' ? '1 gekoppeld apparaat' : ($lang === 'de' ? '1 verbundenes Gerät' : '1 linked device'))
        : ($lang === 'nl' ? 'Tot 5 gekoppelde apparaten' : ($lang === 'de' ? 'Bis zu 5 verbundene Geräte' : 'Up to 5 linked devices'));

    echo '<div class="nv-product-plan-panel">';
    echo '<span class="nv-product-plan-badge">' . esc_html($badge) . '</span>';
    echo '<p>' . esc_html($text) . '</p>';
    echo '<p><strong>' . esc_html($device_text) . '</strong> · ' . esc_html($lang === 'nl' ? 'dezelfde Pro-functies in Solo en Multi' : ($lang === 'de' ? 'dieselben Pro-Funktionen in Solo und Multi' : 'the same Pro features in Solo and Multi')) . '</p>';
    echo '<div class="nv-product-benefits">'
        . '<span><strong>✓</strong>' . esc_html($s['product_auto']) . '</span>'
        . '<span><strong>✓</strong>' . esc_html($s['product_secure']) . '</span>'
        . '<span><strong>✓</strong>' . esc_html($s['product_support']) . '</span>'
        . '</div></div>';
}
add_action('woocommerce_single_product_summary', 'nenotv_product_plan_panel', 8);

function nenotv_product_after_cart_trust(): void {
    $s = nenotv_commerce_strings();
    echo '<div class="nv-product-after-cart">' . nenotv_commerce_trust($s) . '</div>';
}
add_action('woocommerce_after_add_to_cart_button', 'nenotv_product_after_cart_trust');

function nenotv_thankyou_panel($order_id): void {
    if (!$order_id || !function_exists('wc_get_order')) return;
    $order = wc_get_order($order_id);
    if (!$order instanceof WC_Order) return;

    $s = nenotv_commerce_strings();
    $lang = nenotv_current_language();
    $account = function_exists('wc_get_page_permalink') ? wc_get_page_permalink('myaccount') : home_url('/my-account/');
    if (in_array($lang, ['nl','de'], true)) $account = add_query_arg('lang', $lang, $account);

    $needs_payment = $order->needs_payment();
    $title = $needs_payment ? nenotv_order_action_text('payment_failed_title') : $s['thank_title'];
    $text = $needs_payment ? nenotv_order_action_text('payment_failed_text') : $s['thank_text'];

    echo '<section class="nv-thankyou-card' . ($needs_payment ? ' is-payment-pending' : '') . '">'
        . '<div class="nv-commerce-mark"><img src="' . esc_url(get_template_directory_uri() . '/assets/brand/nenotv-mark.svg') . '" width="108" height="108" alt=""></div>'
        . '<div><div class="nv-commerce-kicker">NenoTV Pro</div><h2>' . esc_html($title) . '</h2><p>' . esc_html($text) . '</p>'
        . '<div class="nv-thankyou-actions">';

    if ($needs_payment) {
        echo '<a class="nv-thankyou-primary" href="' . esc_url($order->get_checkout_payment_url()) . '">' . esc_html(nenotv_order_action_text('retry')) . '</a>';
    } else {
        echo '<a class="nv-thankyou-primary" href="' . esc_url($account) . '">' . esc_html(nenotv_order_action_text('account')) . '</a>';
    }

    echo '</div></div></section>';
}
add_action('woocommerce_thankyou', 'nenotv_thankyou_panel', 5);





function nenotv_order_action_text(string $type): string {
    $lang = nenotv_current_language();
    $labels = [
        'retry' => ['en'=>'Try payment again','nl'=>'Opnieuw betalen','de'=>'Zahlung erneut versuchen'],
        'account' => ['en'=>'Open My NenoTV','nl'=>'Open My NenoTV','de'=>'My NenoTV öffnen'],
        'payment_failed_title' => ['en'=>'Payment not completed','nl'=>'Betaling niet afgerond','de'=>'Zahlung nicht abgeschlossen'],
        'payment_failed_text' => ['en'=>'Your order is saved. You can safely try the payment again without creating another order.','nl'=>'Je bestelling is bewaard. Je kunt de betaling veilig opnieuw proberen zonder een nieuwe bestelling te maken.','de'=>'Deine Bestellung ist gespeichert. Du kannst die Zahlung sicher erneut versuchen, ohne eine neue Bestellung anzulegen.'],
    ];
    return $labels[$type][$lang] ?? $labels[$type]['en'];
}

function nenotv_my_account_order_actions(array $actions, $order): array {
    if (!$order instanceof WC_Order) return $actions;
    if ($order->needs_payment()) {
        if (isset($actions['pay'])) {
            $actions['pay']['name'] = nenotv_order_action_text('retry');
        } else {
            $actions['nenotv_retry'] = [
                'url' => $order->get_checkout_payment_url(),
                'name' => nenotv_order_action_text('retry'),
            ];
        }
    }
    return $actions;
}
add_filter('woocommerce_my_account_my_orders_actions', 'nenotv_my_account_order_actions', 20, 2);

function nenotv_order_retry_action($order): void {
    if (!$order instanceof WC_Order || !$order->needs_payment()) return;
    if (is_user_logged_in() && (int)$order->get_customer_id() !== get_current_user_id() && !current_user_can('manage_woocommerce')) return;
    echo '<div class="nv-order-retry"><p>' . esc_html(nenotv_order_action_text('payment_failed_text')) . '</p><a class="button" href="' . esc_url($order->get_checkout_payment_url()) . '">' . esc_html(nenotv_order_action_text('retry')) . '</a></div>';
}
add_action('woocommerce_order_details_after_order_table', 'nenotv_order_retry_action', 8, 1);

function nenotv_simplify_sales_routes(): void {
    if (is_admin() || wp_doing_ajax()) return;

    if ((function_exists('is_shop') && is_shop())
        || (function_exists('is_product_taxonomy') && is_product_taxonomy())) {
        wp_safe_redirect(nenotv_public_page_url('pricing'), 301);
        exit;
    }

    if (function_exists('is_product') && is_product() && function_exists('wc_get_product')) {
        $product = wc_get_product(get_queried_object_id());
        if ($product instanceof WC_Product && str_starts_with(strtoupper((string)$product->get_sku()), 'NENOTV-PRO')) {
            wp_safe_redirect(nenotv_public_page_url('pricing'), 301);
            exit;
        }
    }
}
add_action('template_redirect', 'nenotv_simplify_sales_routes', 5);

function nenotv_hide_internal_products_from_catalog($visible, $product_id) {
    if (!function_exists('wc_get_product')) return $visible;
    $product = wc_get_product($product_id);
    if ($product instanceof WC_Product && str_starts_with(strtoupper((string)$product->get_sku()), 'NENOTV-PRO')) {
        return false;
    }
    return $visible;
}
add_filter('woocommerce_product_is_visible', 'nenotv_hide_internal_products_from_catalog', 20, 2);

function nenotv_exclude_product_sitemap($excluded, $post_type) {
    return $post_type === 'product' ? true : $excluded;
}
add_filter('wpseo_sitemap_exclude_post_type', 'nenotv_exclude_product_sitemap', 20, 2);

function nenotv_public_sales_ready(): bool {
    $mollie_live_key = trim((string)get_option('mollie-payments-for-woocommerce_live_api_key', ''));
    $mollie_live_ready = get_option('mollie-payments-for-woocommerce_test_mode_enabled', 'yes') !== 'yes'
        && (bool)preg_match('/^live_\w{30,}$/', $mollie_live_key);
    $bridge_secret = (string)get_option('nenotv_bridge_secret', '');
    $bridge_ready = get_option('nenotv_bridge_enabled', '0') === '1' && strlen($bridge_secret) >= 32;
    $trial_ready = get_option('nenotv_trial_enabled', '0') === '1'
        && absint(get_option('nenotv_trial_days', 30)) === 30;

    $products_ready = function_exists('wc_get_product_id_by_sku');
    if ($products_ready) {
        foreach (['NENOTV-PRO-1-YEARLY','NENOTV-PRO-1-LIFETIME','NENOTV-PRO-5-YEARLY','NENOTV-PRO-5-LIFETIME'] as $sku) {
            $id = (int)wc_get_product_id_by_sku($sku);
            if (!$id || get_post_status($id) !== 'publish') {
                $products_ready = false;
                break;
            }
        }
    }

    $ready = get_option('nenotv_public_sales_enabled', 'no') === 'yes'
        && get_option('nenotv_public_plan_structure_ready', 'no') === 'yes'
        && get_option('woocommerce_coming_soon', 'yes') !== 'yes'
        && get_option('nenotv_entitlement_mode', 'shadow') === 'live'
        && $mollie_live_ready
        && $bridge_ready
        && $trial_ready
        && $products_ready;

    return (bool)apply_filters('nenotv_public_sales_ready_state', $ready);
}

function nenotv_plan_selector_strings(): array {
    $lang = nenotv_current_language();
    $all = [
        'en' => [
            'kicker'=>'Choose NenoTV Pro','title'=>'Choose Solo or Multi','intro'=>'Eligible new users start with 30 days of the complete Pro experience. Without a Pro purchase, NenoTV continues as Light after the trial. Both paid tiers include the same Pro features; only the number of linked devices changes.',
            'solo'=>'Solo – NenoTV Pro','solo_sub'=>'For one TV, phone or player device','multi'=>'Multi – NenoTV Pro','multi_sub'=>'For your household or multiple devices',
            'one_device'=>'1 linked device','five_devices'=>'Up to 5 linked devices','same_features'=>'All NenoTV Pro features','activation'=>'Automatic Pro activation','account'=>'NenoTV account included','content'=>'No IPTV service or content included',
            'yearly'=>'Yearly','lifetime'=>'Lifetime','yearly_note'=>'One year from successful payment · no automatic renewal','lifetime_note'=>'One-time purchase',
            'choose'=>'Choose','soon'=>'Coming soon','launch'=>'Launch price','safe'=>'Current configured prices are shown for pre-launch reference. Public Pro purchasing is still disabled.'
        ],
        'nl' => [
            'kicker'=>'Kies NenoTV Pro','title'=>'Kies Solo of Multi','intro'=>'Geschikte nieuwe gebruikers starten met 30 dagen de volledige Pro-ervaring. Zonder Pro-aankoop gaat NenoTV daarna verder als Light. Beide betaalde varianten hebben dezelfde Pro-functies; alleen het aantal gekoppelde apparaten verschilt.',
            'solo'=>'Solo – NenoTV Pro','solo_sub'=>'Voor één tv, telefoon of speler','multi'=>'Multi – NenoTV Pro','multi_sub'=>'Voor je huishouden of meerdere apparaten',
            'one_device'=>'1 gekoppeld apparaat','five_devices'=>'Tot 5 gekoppelde apparaten','same_features'=>'Alle NenoTV Pro-functies','activation'=>'Automatische Pro-activatie','account'=>'NenoTV-account inbegrepen','content'=>'Geen IPTV-dienst of content inbegrepen',
            'yearly'=>'Jaarlijks','lifetime'=>'Lifetime','yearly_note'=>'Eén jaar vanaf succesvolle betaling · geen automatische verlenging','lifetime_note'=>'Eenmalige aankoop',
            'choose'=>'Kies','soon'=>'Binnenkort','launch'=>'Launchprijs','safe'=>'De huidige ingestelde prijzen worden al getoond ter controle. Publieke Pro-aankoop staat nog uit.'
        ],
        'de' => [
            'kicker'=>'NenoTV Pro wählen','title'=>'Solo oder Multi wählen','intro'=>'Berechtigte neue Nutzer starten mit 30 Tagen der vollständigen Pro-Erfahrung. Ohne Pro-Kauf läuft NenoTV danach als Light weiter. Beide kostenpflichtigen Varianten enthalten dieselben Pro-Funktionen; nur die Anzahl der verbundenen Geräte unterscheidet sich.',
            'solo'=>'Solo – NenoTV Pro','solo_sub'=>'Für einen Fernseher, ein Telefon oder Player-Gerät','multi'=>'Multi – NenoTV Pro','multi_sub'=>'Für den Haushalt oder mehrere Geräte',
            'one_device'=>'1 verbundenes Gerät','five_devices'=>'Bis zu 5 verbundene Geräte','same_features'=>'Alle NenoTV Pro-Funktionen','activation'=>'Automatische Pro-Aktivierung','account'=>'NenoTV-Konto inklusive','content'=>'Kein IPTV-Dienst oder Content enthalten',
            'yearly'=>'Jährlich','lifetime'=>'Lifetime','yearly_note'=>'Ein Jahr ab erfolgreicher Zahlung · keine automatische Verlängerung','lifetime_note'=>'Einmaliger Kauf',
            'choose'=>'Wählen','soon'=>'Demnächst','launch'=>'Launch-Preis','safe'=>'Die aktuell konfigurierten Preise werden zur Pre-launch-Kontrolle angezeigt. Öffentliche Pro-Käufe sind noch deaktiviert.'
        ],
    ];
    return $all[$lang] ?? $all['en'];
}



function nenotv_plan_price_html($product, string $lang): string {
    if (!$product instanceof WC_Product) return '';
    $format = static function($amount) use ($lang): string {
        $number = number_format((float)$amount, 2, $lang === 'en' ? '.' : ',', '');
        return $lang === 'de' ? $number . ' €' : '€' . $number;
    };

    $regular = (string)$product->get_regular_price();
    $sale = (string)$product->get_sale_price();
    $current = (string)$product->get_price();

    if ($sale !== '' && $regular !== '' && (float)$sale < (float)$regular) {
        return '<del>' . esc_html($format($regular)) . '</del> <ins>' . esc_html($format($sale)) . '</ins>';
    }
    return esc_html($format($current !== '' ? $current : $regular));
}

function nenotv_plan_selector_shortcode(): string {
    if (!function_exists('wc_get_product_id_by_sku')) return '';
    $s = nenotv_plan_selector_strings();
    $lang = nenotv_current_language();
    $ready = nenotv_public_sales_ready();

    // Prices can be shown during pre-launch while purchase CTAs remain disabled.
    $tiers = [
        'solo' => [
            'name'=>$s['solo'],'sub'=>$s['solo_sub'],'device'=>$s['one_device'],'featured'=>false,
            'yearly'=>['key'=>'solo-yearly','sku'=>'NENOTV-PRO-1-YEARLY'],
            'lifetime'=>['key'=>'solo-lifetime','sku'=>'NENOTV-PRO-1-LIFETIME'],
        ],
        'multi' => [
            'name'=>$s['multi'],'sub'=>$s['multi_sub'],'device'=>$s['five_devices'],'featured'=>true,
            'yearly'=>['key'=>'multi-yearly','sku'=>'NENOTV-PRO-5-YEARLY'],
            'lifetime'=>['key'=>'multi-lifetime','sku'=>'NENOTV-PRO-5-LIFETIME'],
        ],
    ];

    $image_alts = [
        'en' => [
            'solo-yearly' => 'Solo Yearly – NenoTV Pro for 1 device',
            'solo-lifetime' => 'Solo Lifetime – NenoTV Pro for 1 device',
            'multi-yearly' => 'Multi Yearly – NenoTV Pro for up to 5 devices',
            'multi-lifetime' => 'Multi Lifetime – NenoTV Pro for up to 5 devices',
        ],
        'nl' => [
            'solo-yearly' => 'Solo Jaarlijks – NenoTV Pro voor 1 apparaat',
            'solo-lifetime' => 'Solo Lifetime – NenoTV Pro voor 1 apparaat',
            'multi-yearly' => 'Multi Jaarlijks – NenoTV Pro voor maximaal 5 apparaten',
            'multi-lifetime' => 'Multi Lifetime – NenoTV Pro voor maximaal 5 apparaten',
        ],
        'de' => [
            'solo-yearly' => 'Solo Jährlich – NenoTV Pro für 1 Gerät',
            'solo-lifetime' => 'Solo Lifetime – NenoTV Pro für 1 Gerät',
            'multi-yearly' => 'Multi Jährlich – NenoTV Pro für bis zu 5 Geräte',
            'multi-lifetime' => 'Multi Lifetime – NenoTV Pro für bis zu 5 Geräte',
        ],
    ];

    $html = '<section id="nenotv-pro-plans" class="nv-plan-selector"><div class="nv-plan-selector-head"><span class="nv-commerce-kicker">' . esc_html($s['kicker']) . '</span><h2>' . esc_html($s['title']) . '</h2><p>' . esc_html($s['intro']) . '</p></div><div class="nv-plan-grid">';
    foreach ($tiers as $tier_key => $tier) {
        $classes = 'nv-plan-card nv-plan-tier-' . $tier_key . ($tier['featured'] ? ' is-featured' : '');
        $html .= '<article class="' . esc_attr($classes) . '">';
        if ($tier['featured']) {
            $launch_id = (int)wc_get_product_id_by_sku('NENOTV-PRO-5-LIFETIME');
            $launch_product = $launch_id ? wc_get_product($launch_id) : null;
            if ($launch_product instanceof WC_Product && $launch_product->is_on_sale()) {
                $html .= '<span class="nv-plan-ribbon">' . esc_html($s['launch']) . '</span>';
            }
        }
        $html .= '<h3>' . esc_html($tier['name']) . '</h3><p class="nv-plan-sub">' . esc_html($tier['sub']) . '</p>';
        $html .= '<ul><li>✓ ' . esc_html($tier['device']) . '</li><li>✓ ' . esc_html($s['same_features']) . '</li><li>✓ ' . esc_html($s['activation']) . '</li><li>✓ ' . esc_html($s['account']) . '</li><li>✓ ' . esc_html($s['content']) . '</li></ul>';
        $html .= '<div class="nv-plan-options">';

        foreach (['yearly','lifetime'] as $term) {
            $cfg = $tier[$term];
            $id = (int)wc_get_product_id_by_sku($cfg['sku']);
            $product = $id ? wc_get_product($id) : null;
            $can_buy = (bool)apply_filters('nenotv_can_buy_plan', true, $cfg['key']);
            $plan_ready = $ready && $can_buy && $product instanceof WC_Product && $product->get_status() === 'publish' && $product->is_purchasable();

            $asset = get_template_directory_uri() . '/assets/brand/pro-' . $cfg['key'] . '-' . $lang . '.svg';
            $alt = $image_alts[$lang][$cfg['key']] ?? ($tier['name'] . ' ' . $s[$term]);
            $html .= '<div class="nv-plan-option"><img class="nv-plan-option-image" src="' . esc_url($asset) . '" alt="' . esc_attr($alt) . '" title="' . esc_attr($alt) . '" loading="lazy" width="1200" height="1200"><div><strong>' . esc_html($s[$term]) . '</strong><span>' . esc_html($s[$term . '_note']) . '</span></div>';
            if ($product instanceof WC_Product) $html .= '<div class="nv-plan-option-price">' . wp_kses_post(nenotv_plan_price_html($product, $lang)) . '</div>';
            if ($plan_ready) {
                $url = add_query_arg(['nenotv-buy'=>$cfg['key'],'lang'=>$lang], home_url('/'));
                $html .= '<a class="nv-plan-option-cta" href="' . esc_url($url) . '">' . esc_html($s['choose']) . '</a>';
            } elseif ($ready && !$can_buy && is_user_logged_in()) {
                $account = function_exists('wc_get_page_permalink') ? wc_get_page_permalink('myaccount') : home_url('/my-account/');
                if (in_array($lang, ['nl','de'], true)) $account = add_query_arg('lang', $lang, $account);
                $html .= '<a class="nv-plan-option-cta" href="' . esc_url($account) . '">' . esc_html(nenotv_order_action_text('account')) . '</a>';
            } else {
                $html .= '<span class="nv-plan-option-cta is-disabled" aria-disabled="true">' . esc_html($s['soon']) . '</span>';
            }
            $html .= '</div>';
        }

        $html .= '</div></article>';
    }
    $html .= '</div>';
    if (!$ready) $html .= '<p class="nv-plan-safe">' . esc_html($s['safe']) . '</p>';
    $html .= '</section>';
    return $html;
}
add_shortcode('nenotv_plan_selector', 'nenotv_plan_selector_shortcode');

function nenotv_pro_product_schema(): void {
    if (!function_exists('is_page') || !is_page() || !nenotv_public_sales_ready() || !function_exists('wc_get_product_id_by_sku')) return;

    $page_id = get_queried_object_id();
    $pricing_ids = array_filter([
        9,
        function_exists('pll_get_post') ? (int)pll_get_post(9, 'nl') : 0,
        function_exists('pll_get_post') ? (int)pll_get_post(9, 'de') : 0,
    ]);
    if (!in_array((int)$page_id, array_map('intval', $pricing_ids), true)) return;

    $lang = nenotv_current_language();
    $pricing_url = get_permalink($page_id);
    $products = [
        'solo-yearly' => ['sku'=>'NENOTV-PRO-1-YEARLY','devices'=>1,'term'=>'yearly'],
        'solo-lifetime' => ['sku'=>'NENOTV-PRO-1-LIFETIME','devices'=>1,'term'=>'lifetime'],
        'multi-yearly' => ['sku'=>'NENOTV-PRO-5-YEARLY','devices'=>5,'term'=>'yearly'],
        'multi-lifetime' => ['sku'=>'NENOTV-PRO-5-LIFETIME','devices'=>5,'term'=>'lifetime'],
    ];

    $names = [
        'en'=>[
            'solo-yearly'=>'Solo Yearly – NenoTV Pro','solo-lifetime'=>'Solo Lifetime – NenoTV Pro',
            'multi-yearly'=>'Multi Yearly – NenoTV Pro','multi-lifetime'=>'Multi Lifetime – NenoTV Pro',
        ],
        'nl'=>[
            'solo-yearly'=>'Solo Jaarlijks – NenoTV Pro','solo-lifetime'=>'Solo Lifetime – NenoTV Pro',
            'multi-yearly'=>'Multi Jaarlijks – NenoTV Pro','multi-lifetime'=>'Multi Lifetime – NenoTV Pro',
        ],
        'de'=>[
            'solo-yearly'=>'Solo Jährlich – NenoTV Pro','solo-lifetime'=>'Solo Lifetime – NenoTV Pro',
            'multi-yearly'=>'Multi Jährlich – NenoTV Pro','multi-lifetime'=>'Multi Lifetime – NenoTV Pro',
        ],
    ];
    $descriptions = [
        'en'=>['yearly'=>'NenoTV Pro player and account features for one year. No automatic renewal.','lifetime'=>'NenoTV Pro player and account features with a one-time lifetime purchase.','content'=>'Media content is not included.'],
        'nl'=>['yearly'=>'NenoTV Pro-speler- en accountfuncties voor één jaar. Geen automatische verlenging.','lifetime'=>'NenoTV Pro-speler- en accountfuncties als eenmalige lifetime aankoop.','content'=>'Mediacontent is niet inbegrepen.'],
        'de'=>['yearly'=>'NenoTV-Pro-Player- und Kontofunktionen für ein Jahr. Keine automatische Verlängerung.','lifetime'=>'NenoTV-Pro-Player- und Kontofunktionen als einmaliger Lifetime-Kauf.','content'=>'Medieninhalte sind nicht enthalten.'],
    ];

    $graph = [];
    foreach ($products as $key=>$cfg) {
        $id = (int)wc_get_product_id_by_sku($cfg['sku']);
        $product = $id ? wc_get_product($id) : null;
        if (!$product instanceof WC_Product || $product->get_status() !== 'publish') continue;
        $price = (string)$product->get_price();
        if ($price === '') continue;

        $device_text = $cfg['devices'] === 1
            ? ($lang === 'nl' ? '1 apparaat' : ($lang === 'de' ? '1 Gerät' : '1 device'))
            : ($lang === 'nl' ? 'maximaal 5 apparaten' : ($lang === 'de' ? 'bis zu 5 Geräte' : 'up to 5 devices'));

        $graph[] = [
            '@type'=>'Product',
            '@id'=>$pricing_url . '#' . $key . '-product',
            'name'=>$names[$lang][$key] ?? $names['en'][$key],
            'sku'=>$cfg['sku'],
            'brand'=>['@type'=>'Brand','name'=>'NenoTV'],
            'image'=>get_template_directory_uri() . '/assets/brand/pro-' . $key . '-' . $lang . '.svg',
            'description'=>($descriptions[$lang][$cfg['term']] ?? $descriptions['en'][$cfg['term']]) . ' ' . $device_text . '. ' . ($descriptions[$lang]['content'] ?? $descriptions['en']['content']),
            'offers'=>[
                '@type'=>'Offer',
                'url'=>$pricing_url . '#nenotv-pro-plans',
                'priceCurrency'=>'EUR',
                'price'=>wc_format_decimal($price, 2),
                'availability'=>'https://schema.org/InStock',
                'seller'=>['@type'=>'Organization','name'=>'Tube Beheer B.V.'],
            ],
        ];
    }

    if (!$graph) return;
    echo "\n<script type=\"application/ld+json\">" . wp_json_encode(['@context'=>'https://schema.org','@graph'=>$graph], JSON_UNESCAPED_SLASHES|JSON_UNESCAPED_UNICODE) . "</script>\n";
}
add_action('wp_head', 'nenotv_pro_product_schema', 35);

function nenotv_handle_direct_buy(): void {
    if (empty($_GET['nenotv-buy'])) return;
    if (!function_exists('wc_get_product_id_by_sku') || !nenotv_public_sales_ready()) {
        wp_safe_redirect(nenotv_public_page_url('pricing'));
        exit;
    }

    $plan = sanitize_key((string)$_GET['nenotv-buy']);
    $sku_map = [
        'solo-yearly' => 'NENOTV-PRO-1-YEARLY',
        'solo-lifetime' => 'NENOTV-PRO-1-LIFETIME',
        'multi-yearly' => 'NENOTV-PRO-5-YEARLY',
        'multi-lifetime' => 'NENOTV-PRO-5-LIFETIME',
    ];
    $sku = $sku_map[$plan] ?? '';
    if ($sku === '') {
        wp_safe_redirect(nenotv_public_page_url('pricing'));
        exit;
    }

    if (!(bool)apply_filters('nenotv_can_buy_plan', true, $plan)) {
        $lang = strtolower(sanitize_key((string)($_GET['lang'] ?? '')));
        $account = function_exists('wc_get_page_permalink') ? wc_get_page_permalink('myaccount') : home_url('/my-account/');
        if (in_array($lang, ['nl','de'], true)) $account = add_query_arg('lang', $lang, $account);
        $account = add_query_arg('purchase_blocked', '1', $account);
        wp_safe_redirect($account);
        exit;
    }

    $product_id = (int)wc_get_product_id_by_sku($sku);
    $product = $product_id ? wc_get_product($product_id) : null;
    if (!$product instanceof WC_Product || $product->get_status() !== 'publish' || !$product->is_purchasable()) {
        wp_safe_redirect(nenotv_public_page_url('pricing'));
        exit;
    }

    if (function_exists('wc_load_cart') && (!function_exists('WC') || !WC()->cart)) wc_load_cart();
    if (!function_exists('WC') || !WC()->cart) {
        wp_safe_redirect(nenotv_public_page_url('pricing'));
        exit;
    }

    WC()->cart->empty_cart();
    $added = WC()->cart->add_to_cart($product_id, 1);
    if (!$added) {
        wp_safe_redirect(nenotv_public_page_url('pricing'));
        exit;
    }

    $lang = strtolower(sanitize_key((string)($_GET['lang'] ?? '')));
    if (in_array($lang, nenotv_supported_language_slugs(), true) && WC()->session) {
        WC()->session->set('nenotv_checkout_language', $lang);
    }
    $checkout = wc_get_checkout_url();
    if (in_array($lang, ['nl','de'], true)) $checkout = add_query_arg('lang', $lang, $checkout);
    wp_safe_redirect($checkout);
    exit;
}
add_action('template_redirect', 'nenotv_handle_direct_buy', 20);

function nenotv_account_overview_shortcode(): string {
    if (!is_user_logged_in() || !function_exists('wc_get_account_endpoint_url')) return '';
    $lang = nenotv_current_language();
    $all = [
        'en'=>['k'=>'Account overview','t'=>'Everything for your NenoTV account','p'=>'Manage your NenoTV account, devices, orders and security from one place. Optional encrypted source sync through My NenoTV requires an enabled service and an active account. Full cloud sync of profiles and favourites remains planned.','o'=>'Orders & licences','os'=>'Review purchases and order status','a'=>'Account & security','as'=>'Update your name, email or password','s'=>'Support','ss'=>'Create a ticket only when you need help'],
        'nl'=>['k'=>'Accountoverzicht','t'=>'Alles voor je NenoTV-account','p'=>'Beheer je NenoTV-account, apparaten, bestellingen en beveiliging op één plek. Optionele, versleutelde bronsynchronisatie via Mijn NenoTV vereist een ingeschakelde dienst en een actief account. Volledige cloudsynchronisatie van profielen en favorieten blijft gepland.','o'=>'Bestellingen & licenties','os'=>'Bekijk aankopen en bestelstatus','a'=>'Account & beveiliging','as'=>'Wijzig naam, e-mail of wachtwoord','s'=>'Support','ss'=>'Maak alleen een ticket aan als je hulp nodig hebt'],
        'de'=>['k'=>'Kontoübersicht','t'=>'Alles für dein NenoTV-Konto','p'=>'Verwalte dein NenoTV-Konto, Geräte, Bestellungen und Sicherheit an einem Ort. Optionale verschlüsselte Quellensynchronisierung über Mein NenoTV benötigt einen aktivierten Dienst und ein aktives Konto. Vollständige Cloud-Synchronisierung von Profilen und Favoriten bleibt geplant.','o'=>'Bestellungen & Lizenzen','os'=>'Käufe und Bestellstatus ansehen','a'=>'Konto & Sicherheit','as'=>'Name, E-Mail oder Passwort ändern','s'=>'Support','ss'=>'Nur bei Bedarf ein Support-Ticket erstellen'],
    ];
    $s = $all[$lang] ?? $all['en'];
    $blocked = !empty($_GET['purchase_blocked']);
    $blocked_text = $lang === 'nl'
        ? 'Je hebt al een actieve Pro-licentie. Beheer die hier; zo voorkomen we dubbele aankopen.'
        : ($lang === 'de'
            ? 'Du hast bereits eine aktive Pro-Lizenz. Verwalte sie hier, damit keine doppelte Bestellung entsteht.'
            : 'You already have an active Pro licence. Manage it here so you do not accidentally buy Pro twice.');
    $orders = wc_get_account_endpoint_url('orders');
    $edit = wc_get_account_endpoint_url('edit-account');
    if (in_array($lang, ['nl','de'], true)) {
        $orders = add_query_arg('lang', $lang, $orders);
        $edit = add_query_arg('lang', $lang, $edit);
    }
    $support = $lang === 'nl' ? home_url('/language/nl/contact-nl/') : ($lang === 'de' ? home_url('/language/de/kontakt/') : home_url('/contact/'));

    return '<section class="nenotv-account-overview">' . ($blocked ? '<div class="nv-account-purchase-guard">' . esc_html($blocked_text) . '</div>' : '') . '<p class="nenotv-account-kicker">' . esc_html($s['k']) . '</p><h2>' . esc_html($s['t']) . '</h2><p>' . esc_html($s['p']) . '</p><nav class="nenotv-account-actions">'
        . '<a href="' . esc_url($orders) . '"><strong>' . esc_html($s['o']) . '</strong><span>' . esc_html($s['os']) . '</span></a>'
        . '<a href="' . esc_url($edit) . '"><strong>' . esc_html($s['a']) . '</strong><span>' . esc_html($s['as']) . '</span></a>'
        . '<a href="' . esc_url($support) . '"><strong>' . esc_html($s['s']) . '</strong><span>' . esc_html($s['ss']) . '</span></a>'
        . (class_exists('NenoTV_Tester_Account') ? '<a href="' . esc_url(NenoTV_Tester_Account::url($lang)) . '"><strong>' . esc_html(NenoTV_Tester_Account::label($lang)) . '</strong><span>' . esc_html(NenoTV_Tester_Account::description($lang)) . '</span></a>' : '')
        . '</nav></section>';
}
add_shortcode('nenotv_account_overview', 'nenotv_account_overview_shortcode');



function nenotv_email_order_language($order = null): string {
    if ($order instanceof WC_Order) {
        $lang = strtolower((string)$order->get_meta('_nenotv_order_language', true));
        if (in_array($lang, ['en','nl','de'], true)) return $lang;
    }
    return 'en';
}

function nenotv_email_order_plan($order): array {
    $result = ['label'=>'NenoTV Pro','devices'=>'—','term'=>'—','upgrade'=>false];
    if (!$order instanceof WC_Order) return $result;

    foreach ($order->get_items() as $item) {
        $product = method_exists($item, 'get_product') ? $item->get_product() : null;
        if (!$product instanceof WC_Product) continue;
        $sku = strtoupper((string)$product->get_sku());

        if ($sku === 'NENOTV-PRO-1-YEARLY') return ['label'=>'Pro Solo','devices'=>'1','term'=>'yearly','upgrade'=>false];
        if ($sku === 'NENOTV-PRO-1-LIFETIME') return ['label'=>'Pro Solo','devices'=>'1','term'=>'lifetime','upgrade'=>false];
        if ($sku === 'NENOTV-PRO-5-YEARLY' || $sku === 'NENOTV-PRO-YEARLY') return ['label'=>'Pro Multi','devices'=>'5','term'=>'yearly','upgrade'=>false];
        if ($sku === 'NENOTV-PRO-5-LIFETIME' || $sku === 'NENOTV-PRO-LIFETIME') return ['label'=>'Pro Multi','devices'=>'5','term'=>'lifetime','upgrade'=>false];
        if ($sku === 'NENOTV-PRO-UPGRADE-MULTI') return ['label'=>'Solo → Multi','devices'=>'5','term'=>(string)$order->get_meta('_nenotv_upgrade_mode', true),'upgrade'=>true];
        if ($sku === 'NENOTV-PRO') return ['label'=>'NenoTV Pro test','devices'=>'—','term'=>'test','upgrade'=>false];
    }
    return $result;
}

function nenotv_email_labels(string $lang): array {
    $all = [
        'en'=>[
            'title'=>'Your NenoTV Pro order','plan'=>'Plan','devices'=>'Device limit','term'=>'Term','yearly'=>'1 year from successful payment',
            'lifetime'=>'Lifetime','annual_prorata'=>'Upgrade for the remaining current term','annual_renew'=>'Upgrade + a new Multi year after the current term',
            'account'=>'Open My NenoTV','support'=>'Need help? Create a support ticket.','note'=>'NenoTV is player software. No IPTV service, channels or content are included.'
        ],
        'nl'=>[
            'title'=>'Je NenoTV Pro-bestelling','plan'=>'Plan','devices'=>'Apparaatlimiet','term'=>'Looptijd','yearly'=>'1 jaar vanaf succesvolle betaling',
            'lifetime'=>'Lifetime','annual_prorata'=>'Upgrade voor de resterende huidige looptijd','annual_renew'=>'Upgrade + een nieuw Multi-jaar na de huidige looptijd',
            'account'=>'Open My NenoTV','support'=>'Hulp nodig? Maak een supportticket aan.','note'=>'NenoTV is spelersoftware. Er zijn geen IPTV-dienst, zenders of content inbegrepen.'
        ],
        'de'=>[
            'title'=>'Deine NenoTV Pro-Bestellung','plan'=>'Plan','devices'=>'Gerätelimit','term'=>'Laufzeit','yearly'=>'1 Jahr ab erfolgreicher Zahlung',
            'lifetime'=>'Lifetime','annual_prorata'=>'Upgrade für die verbleibende aktuelle Laufzeit','annual_renew'=>'Upgrade + ein neues Multi-Jahr nach der aktuellen Laufzeit',
            'account'=>'My NenoTV öffnen','support'=>'Hilfe nötig? Support-Ticket erstellen.','note'=>'NenoTV ist Player-Software. IPTV-Dienst, Sender oder Inhalte sind nicht enthalten.'
        ],
    ];
    return $all[$lang] ?? $all['en'];
}

function nenotv_email_pro_summary($order, $sent_to_admin, $plain_text, $email): void {
    if ($sent_to_admin || !$order instanceof WC_Order) return;

    $plan = nenotv_email_order_plan($order);
    if ($plan['term'] === '—') return;

    $lang = nenotv_email_order_language($order);
    $s = nenotv_email_labels($lang);
    $term = $s[$plan['term']] ?? ucfirst(str_replace('_',' ',(string)$plan['term']));
    $device_text = $plan['devices'] === '1'
        ? ($lang === 'nl' ? '1 apparaat' : ($lang === 'de' ? '1 Gerät' : '1 device'))
        : ($plan['devices'] === '5' ? ($lang === 'nl' ? 'maximaal 5 apparaten' : ($lang === 'de' ? 'bis zu 5 Geräte' : 'up to 5 devices')) : '—');

    $account = function_exists('wc_get_page_permalink') ? wc_get_page_permalink('myaccount') : home_url('/my-account/');
    $support = $lang === 'nl' ? home_url('/language/nl/contact-nl/') : ($lang === 'de' ? home_url('/language/de/kontakt/') : home_url('/contact/'));
    if (in_array($lang, ['nl','de'], true)) $account = add_query_arg('lang', $lang, $account);

    if ($plain_text) {
        echo "\n" . $s['title'] . "\n";
        echo $s['plan'] . ': ' . $plan['label'] . "\n";
        echo $s['devices'] . ': ' . $device_text . "\n";
        echo $s['term'] . ': ' . $term . "\n";
        echo $s['account'] . ': ' . $account . "\n";
        echo $s['support'] . ' ' . $support . "\n";
        echo $s['note'] . "\n";
        return;
    }

    echo '<div class="nv-email-pro-card" style="margin:28px 0;padding:22px;border-radius:18px;background:#111820;color:#ffffff;">';
    echo '<div style="font-size:12px;font-weight:800;letter-spacing:.08em;text-transform:uppercase;color:#ffd400;margin-bottom:7px;">NenoTV Pro</div>';
    echo '<h2 style="margin:0 0 16px;color:#ffffff;font-size:22px;">' . esc_html($s['title']) . '</h2>';
    echo '<table role="presentation" cellspacing="0" cellpadding="0" style="width:100%;border-collapse:collapse;color:#ffffff;">';
    echo '<tr><td style="padding:8px 0;color:#aeb8c3;">' . esc_html($s['plan']) . '</td><td style="padding:8px 0;text-align:right;font-weight:800;color:#ffffff;">' . esc_html($plan['label']) . '</td></tr>';
    echo '<tr><td style="padding:8px 0;color:#aeb8c3;">' . esc_html($s['devices']) . '</td><td style="padding:8px 0;text-align:right;font-weight:800;color:#ffffff;">' . esc_html($device_text) . '</td></tr>';
    echo '<tr><td style="padding:8px 0;color:#aeb8c3;">' . esc_html($s['term']) . '</td><td style="padding:8px 0;text-align:right;font-weight:800;color:#ffffff;">' . esc_html($term) . '</td></tr>';
    echo '</table>';
    echo '<p style="margin:18px 0 14px;"><a href="' . esc_url($account) . '" style="display:inline-block;padding:12px 18px;border-radius:999px;background:#ffd400;color:#0a0a0a;text-decoration:none;font-weight:900;">' . esc_html($s['account']) . '</a></p>';
    echo '<p style="margin:0 0 8px;font-size:13px;color:#b8c2cc;"><a href="' . esc_url($support) . '" style="color:#ffd400;text-decoration:none;">' . esc_html($s['support']) . '</a></p>';
    echo '<p style="margin:0;font-size:12px;line-height:1.55;color:#8793a0;">' . esc_html($s['note']) . '</p>';
    echo '</div>';
}
add_action('woocommerce_email_after_order_table', 'nenotv_email_pro_summary', 18, 4);

function nenotv_email_footer_branding($text, $email = null) {
    $order = is_object($email) && isset($email->object) && $email->object instanceof WC_Order ? $email->object : null;
    $lang = nenotv_email_order_language($order);
    $note = $lang === 'nl'
        ? 'NenoTV is spelersoftware en levert geen IPTV-dienst, zenders of content.'
        : ($lang === 'de'
            ? 'NenoTV ist Player-Software und liefert keinen IPTV-Dienst, Sender oder Inhalte.'
            : 'NenoTV is player software and does not provide IPTV service, channels or content.');

    $vat_label = $lang === 'nl' ? 'Btw-id' : ($lang === 'de' ? 'USt-IdNr.' : 'VAT ID');
    $vat_id = (string)get_option('nenotv_vat_id', '');
    $vat_line = $vat_id !== '' ? esc_html($vat_label) . ': ' . esc_html($vat_id) . '<br>' : '';
    return 'NenoTV · Tube Beheer B.V.<br>Amazonestroom 248, 2721 ES Zoetermeer, Netherlands<br>' . $vat_line . 'info@nenotv.com · nenotv.com<br><span style="font-size:11px;">' . esc_html($note) . '</span>';
}
add_filter('woocommerce_email_footer_text', 'nenotv_email_footer_branding', 30, 2);

function nenotv_email_styles($css, $email) {
    $css .= '
        body, #wrapper { background-color:#0b1118 !important; }
        #template_container { border:0 !important; border-radius:22px !important; overflow:hidden !important; box-shadow:0 18px 50px rgba(0,0,0,.18) !important; }
        #template_header { background-color:#111820 !important; border-bottom:1px solid #27313c !important; }
        #template_header h1, #template_header h1 a { color:#ffffff !important; }
        #body_content, #body_content_inner { background-color:#ffffff !important; color:#111820 !important; }
        #body_content h1, #body_content h2, #body_content h3 { color:#111820 !important; }
        #body_content a { color:#176d53; }
        #body_content .button, #body_content a.button, a.button { background-color:#ffd400 !important; color:#0a0a0a !important; border-radius:999px !important; font-weight:800 !important; }
        #body_content table td, #body_content table th { border-color:#e3e8ed !important; }
        #template_footer { background-color:#0b1118 !important; }
        #template_footer p, #template_footer td { color:#aeb8c3 !important; }
        .nv-email-pro-card a { color:#ffd400; }
    ';
    return $css;
}
add_filter('woocommerce_email_styles', 'nenotv_email_styles', 20, 2);

function nenotv_public_hreflang_languages($urls) {
    if (!is_array($urls)) return $urls;
    foreach ($urls as $language => $url) {
        if ($language === 'x-default') continue;
        if (!preg_match('/^(en|nl|de)([-_]|$)/i', (string)$language)) {
            unset($urls[$language]);
        }
    }
    $default_url = '';
    foreach ($urls as $language => $url) {
        if (preg_match('/^en([-_]|$)/i', (string)$language)) {
            $default_url = (string)$url;
            break;
        }
    }
    if ($default_url !== '') {
        $urls['x-default'] = $default_url;
    }

    $GLOBALS['nenotv_pll_hreflangs_seen'] = $urls;
    return $urls;
}
add_filter('pll_rel_hreflang_attributes', 'nenotv_public_hreflang_languages');

function nenotv_hreflang_fallback(): void {
    if (is_admin() || is_404() || is_search() || is_paged()) return;
    if (!empty($GLOBALS['nenotv_pll_hreflangs_seen']) && count((array)$GLOBALS['nenotv_pll_hreflangs_seen']) > 1) return;
    if (!function_exists('pll_get_post_translations')) return;

    if ((function_exists('is_cart') && is_cart())
        || (function_exists('is_checkout') && is_checkout())
        || (function_exists('is_account_page') && is_account_page())) {
        return;
    }

    $post_id = (int)get_queried_object_id();
    if ($post_id <= 0) {
        $request_path = (string)wp_parse_url((string)($_SERVER['REQUEST_URI'] ?? ''), PHP_URL_PATH);
        if ($request_path !== '') $post_id = (int)url_to_postid(home_url($request_path));
    }
    if ($post_id <= 0 || get_post_status($post_id) !== 'publish') return;
    if ((string)get_post_meta($post_id, '_yoast_wpseo_meta-robots-noindex', true) === '1') return;

    $translations = pll_get_post_translations($post_id);
    if (!is_array($translations)) return;

    $urls = [];
    foreach (nenotv_supported_language_slugs() as $lang) {
        $translated_id = isset($translations[$lang]) ? (int)$translations[$lang] : 0;
        if ($translated_id <= 0 || get_post_status($translated_id) !== 'publish') continue;
        $url = get_permalink($translated_id);
        if ($url) $urls[$lang] = $url;
    }
    if (count($urls) < 2) return;
    if (!empty($urls['en'])) $urls['x-default'] = $urls['en'];

    foreach ($urls as $lang => $url) {
        printf("<link rel=\"alternate\" href=\"%s\" hreflang=\"%s\" />\n", esc_url($url), esc_attr($lang));
    }
}
add_action('wp_head', 'nenotv_hreflang_fallback', 2);

function nenotv_post_view_url($post) {
    if (!$post instanceof WP_Post) return home_url('/');
    if ($post->post_status === 'publish') return get_permalink($post);
    if (current_user_can('edit_post', $post->ID)) {
        $preview = get_preview_post_link($post);
        if ($preview) return $preview;
    }
    return home_url('/');
}

function nenotv_status_public_url_for_id($post_id): string {
    $map = [
        966 => '/language/nl/status/',
        967 => '/language/de/status/',
    ];
    return isset($map[(int)$post_id]) ? home_url($map[(int)$post_id]) : '';
}

function nenotv_status_page_link($url, $post_id) {
    $pretty = nenotv_status_public_url_for_id((int)$post_id);
    return $pretty !== '' ? $pretty : $url;
}
add_filter('page_link', 'nenotv_status_page_link', 9999, 2);

function nenotv_status_rewrite_rules(): void {
    add_rewrite_rule('^language/nl/status/?$', 'index.php?page_id=966&lang=nl', 'top');
    add_rewrite_rule('^language/de/status/?$', 'index.php?page_id=967&lang=de', 'top');

    if (get_option('nenotv_status_rewrite_v1') !== '1') {
        flush_rewrite_rules(false);
        update_option('nenotv_status_rewrite_v1', '1', false);
    }
}
add_action('init', 'nenotv_status_rewrite_rules', 20);

function nenotv_status_legacy_redirects(): void {
    $path = (string)wp_parse_url((string)($_SERVER['REQUEST_URI'] ?? ''), PHP_URL_PATH);
    $redirects = [
        '/language/nl/status-2/' => home_url('/language/nl/status/'),
        '/language/de/status-3/' => home_url('/language/de/status/'),
    ];
    if (isset($redirects[$path])) {
        wp_safe_redirect($redirects[$path], 301);
        exit;
    }
}
add_action('template_redirect', 'nenotv_status_legacy_redirects', 1);

function nenotv_status_yoast_canonical($canonical) {
    if (is_page(966)) return home_url('/language/nl/status/');
    if (is_page(967)) return home_url('/language/de/status/');
    return $canonical;
}
add_filter('wpseo_canonical', 'nenotv_status_yoast_canonical', 20);

function nenotv_status_yoast_opengraph_url($url) {
    if (is_page(966)) return home_url('/language/nl/status/');
    if (is_page(967)) return home_url('/language/de/status/');
    return $url;
}
add_filter('wpseo_opengraph_url', 'nenotv_status_yoast_opengraph_url', 20);

function nenotv_status_yoast_schema_webpage($data) {
    if (!is_array($data)) return $data;
    $pretty = is_page(966)
        ? home_url('/language/nl/status/')
        : (is_page(967) ? home_url('/language/de/status/') : '');
    if ($pretty === '') return $data;

    $old_id = isset($data['@id']) ? (string)$data['@id'] : '';
    $data['url'] = $pretty;
    if ($old_id !== '') {
        $fragment = (string)wp_parse_url($old_id, PHP_URL_FRAGMENT);
        $data['@id'] = $pretty . ($fragment !== '' ? '#' . $fragment : '');
    }
    return $data;
}
add_filter('wpseo_schema_webpage', 'nenotv_status_yoast_schema_webpage', 20);



