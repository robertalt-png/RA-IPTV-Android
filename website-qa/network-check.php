<?php
$curl = curl_init('https://nenotv.com/');
curl_setopt_array($curl, array(CURLOPT_RETURNTRANSFER => true, CURLOPT_CONNECTTIMEOUT => 3, CURLOPT_TIMEOUT => 5));
$response = curl_exec($curl);
$error = curl_errno($curl);
curl_close($curl);
// A certificate failure means TLS was reached and is not an isolation success.
if ($response !== false || !in_array($error, array(6, 7, 28), true)) {
    throw new RuntimeException('Raw cURL reached an external service; errno=' . $error);
}
echo "Direct outbound connection blocked.\n";
