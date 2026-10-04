<?php
/** Bounded catalog parsers and sealed gzip parts. No WordPress dependency. */
final class NenoTV_Catalog_Format {
    const MAX_BYTES=104857600;
    const MAX_ROWS=500000;
    const MAX_LINE=1048576;
    public static function json_array(string $file, callable $emit): int {
        $h=fopen($file,'rb');if(!$h)throw new RuntimeException('catalog_read');
        $first=fread($h,2);rewind($h);$gzip=$first==="\x1f\x8b";
        if($gzip){fclose($h);$h=gzopen($file,'rb');}
        $state='start';$token='';$depth=0;$quoted=false;$escape=false;$bytes=0;$count=0;
        try {
            while(!($gzip?gzeof($h):feof($h))){
                $chunk=$gzip?gzread($h,65536):fread($h,65536);if($chunk===false)throw new RuntimeException('catalog_read');
                $bytes+=strlen($chunk);if($bytes>self::MAX_BYTES)throw new RuntimeException('catalog_size');
                for($i=0,$n=strlen($chunk);$i<$n;$i++){
                    $c=$chunk[$i];
                    if($state==='object'){
                        $token.=$c;if(strlen($token)>self::MAX_LINE)throw new RuntimeException('catalog_row_size');
                        if($quoted){if($escape)$escape=false;elseif($c==='\\')$escape=true;elseif($c==='"')$quoted=false;}
                        elseif($c==='"')$quoted=true;elseif($c==='{'||$c==='[')$depth++;elseif($c==='}'||$c===']')$depth--;
                        if($depth===0){$row=json_decode($token,true,64,JSON_THROW_ON_ERROR);if(!is_array($row))throw new RuntimeException('catalog_row');$emit($row);if(++$count>self::MAX_ROWS)throw new RuntimeException('catalog_rows');$token='';$state='after';}
                        continue;
                    }
                    if(ctype_space($c))continue;
                    if($state==='start'&&$c==='['){$state='first';continue;}
                    if(($state==='first'||$state==='next')&&$c==='{'){$state='object';$token='{';$depth=1;continue;}
                    if(($state==='first'||$state==='after')&&$c===']'){$state='end';continue;}
                    if($state==='after'&&$c===','){$state='next';continue;}
                    throw new RuntimeException('catalog_json');
                }
            }
            if($state!=='end')throw new RuntimeException('catalog_truncated');return $count;
        } finally {$gzip?gzclose($h):fclose($h);}
    }
    public static function m3u(string $file,callable $emit): int {
        $h=fopen($file,'rb');if(!$h)throw new RuntimeException('catalog_read');$count=0;$bytes=0;$pending=null;$header=false;
        try{while(($line=fgets($h,self::MAX_LINE+2))!==false){
            $bytes+=strlen($line);if($bytes>self::MAX_BYTES||strlen($line)>self::MAX_LINE)throw new RuntimeException('catalog_size');
            $line=trim($line,"\xEF\xBB\xBF \r\n\t");if($line==='')continue;
            if(!$header){if(!str_starts_with($line,'#EXTM3U'))throw new RuntimeException('catalog_m3u');$header=true;continue;}
            if(str_starts_with($line,'#EXTINF:')){
                preg_match_all('/([\w-]+)="([^"]*)"/',$line,$matches,PREG_SET_ORDER);$a=[];foreach($matches as $m)$a[strtolower($m[1])]=$m[2];
                // Commas inside quoted attributes are not the title separator.
                $q=false;$comma=false;for($i=0;$i<strlen($line);$i++){if($line[$i]==='"')$q=!$q;elseif($line[$i]===','&&!$q){$comma=$i;break;}}
                $name=$comma===false?($a['tvg-name']??'Untitled'):trim(substr($line,$comma+1));
                $pending=['id'=>$a['tvg-id']??$a['tvg-name']??$name,'name'=>$name,'logo'=>$a['tvg-logo']??'','group'=>$a['group-title']??'Other','tvgId'=>$a['tvg-id']??'','tvgName'=>$a['tvg-name']??$name,'type'=>'live','categoryId'=>$a['group-title']??'Other','catchup'=>!in_array(strtolower($a['catchup']??$a['timeshift']??''),['','0','false','none'],true),'catchupDays'=>(int)($a['catchup-days']??$a['timeshift']??0)];
            }elseif($line[0]!=='#'&&$pending!==null){
                if(!preg_match('~^https?://~i',$line))throw new RuntimeException('catalog_stream_url');
                $pending['url']=$line;$pending['candidates']=[$line];$emit($pending);$pending=null;if(++$count>self::MAX_ROWS)throw new RuntimeException('catalog_rows');
            }
        }if(!$header||$pending!==null||$count===0)throw new RuntimeException('catalog_m3u');return $count;}finally{fclose($h);}
    }
    public static function xtream(array $x,string $type,array $s): array {
        $id=(string)($x[$type==='series'?'series_id':'stream_id']??'');if($id==='')throw new RuntimeException('catalog_item_id');
        $name=(string)($x['name']??$x['title']??'Untitled');$base=rtrim($s['server'],'/');$u=rawurlencode($s['username']);$p=rawurlencode($s['password']);$ext=(string)($x['container_extension']??'mp4');
        $direct=(string)($x['direct_source']??'');$back=$x['backdrop_path']??'';if(is_array($back))$back=$back[0]??'';
        $candidates=[];if(preg_match('~^https?://~',$direct))$candidates[]=$direct;
        elseif($type==='live')$candidates=[$base.'/live/'.$u.'/'.$p.'/'.rawurlencode($id).'.ts',$base.'/live/'.$u.'/'.$p.'/'.rawurlencode($id).'.m3u8',$base.'/'.$u.'/'.$p.'/'.rawurlencode($id)];
        elseif($type==='vod')$candidates=[$base.'/movie/'.$u.'/'.$p.'/'.rawurlencode($id).'.'.rawurlencode($ext)];
        return ['id'=>$id,'streamId'=>$type==='series'?'':$id,'seriesId'=>$type==='series'?$id:'','name'=>$name,'logo'=>(string)($x['stream_icon']??$x['cover']??$x['movie_image']??''),'backdrop'=>(string)($back?:($x['cover_big']??'')),'categoryId'=>(string)($x['category_id']??''),'tvgId'=>(string)($x['epg_channel_id']??$x['tvg_id']??''),'tvgName'=>$name,'type'=>$type,'rating'=>(string)($x['rating']??''),'year'=>(string)($x['year']??$x['releaseDate']??''),'plot'=>(string)($x['plot']??''),'extension'=>$ext,'tmdbId'=>(string)($x['tmdb_id']??$x['tmdb']??''),'imdbId'=>(string)($x['imdb_id']??$x['imdb']??''),'directSource'=>$direct,'catchup'=>(int)($x['tv_archive']??0)>0,'catchupDays'=>(int)($x['tv_archive_duration']??0),'candidates'=>$candidates];
    }
    public static function line($gzip,array $row): void {
        $line=json_encode($row,JSON_UNESCAPED_SLASHES|JSON_UNESCAPED_UNICODE|JSON_THROW_ON_ERROR)."\n";
        if(strlen($line)>self::MAX_LINE||gzwrite($gzip,$line)!==strlen($line))throw new RuntimeException('catalog_write');
    }
    public static function seal(string $plain,string $target,string $key): int {
        $in=fopen($plain,'rb');$out=fopen($target,'xb');if(!$in||!$out)throw new RuntimeException('catalog_storage');chmod($target,0600);$total=0;
        try{while(!feof($in)){$b=fread($in,65536);if($b===false)throw new RuntimeException('catalog_read');if($b==='')break;$iv=random_bytes(12);$tag='';$enc=openssl_encrypt($b,'aes-256-gcm',$key,OPENSSL_RAW_DATA,$iv,$tag,'nenotv-catalog-v1');if($enc===false)throw new RuntimeException('catalog_encrypt');$record=pack('N',strlen($enc)).$iv.$tag.$enc;if(fwrite($out,$record)!==strlen($record))throw new RuntimeException('catalog_write');$total+=strlen($b);}return $total;}finally{fclose($in);fclose($out);}
    }
    public static function unseal(string $file,string $key,callable $emit): void {
        $h=fopen($file,'rb');if(!$h)throw new RuntimeException('catalog_read');
        try{while(!feof($h)){$head=fread($h,4);if($head==='')break;if(strlen($head)!==4)throw new RuntimeException('catalog_seal');$len=unpack('N',$head)[1];if($len<1||$len>65536)throw new RuntimeException('catalog_seal');$frame='';while(strlen($frame)<28+$len){$b=fread($h,28+$len-strlen($frame));if($b===false||$b==='')throw new RuntimeException('catalog_seal');$frame.=$b;}$plain=openssl_decrypt(substr($frame,28),'aes-256-gcm',$key,OPENSSL_RAW_DATA,substr($frame,0,12),substr($frame,12,16),'nenotv-catalog-v1');if($plain===false)throw new RuntimeException('catalog_seal');$emit($plain);}}finally{fclose($h);}
    }
}
