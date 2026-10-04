package com.nenotv.player.entitlement;

import com.nenotv.player.model.*;
import com.nenotv.player.storage.SearchIndexStore;
import com.nenotv.player.net.StreamingJsonArray;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.GZIPInputStream;

/** One authenticated, verified gzip download; atomic replacement of the whole catalog. */
public final class CatalogPackageImporter {
    public static final long MAX_COMPRESSED=100L*1024*1024, MAX_EXPANDED=300L*1024*1024;
    public interface Progress {void update(int items);}
    private CatalogPackageImporter(){}
    static String sha256(File file)throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(file)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1){StreamingJsonArray.checkCancelled();digest.update(b,0,n);}}
        StringBuilder s=new StringBuilder();for(byte b:digest.digest())s.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return s.toString();
    }
    public static int importFile(File file,JSONObject manifest,SearchIndexStore store,String profile,Progress progress,Runnable guard)throws Exception {
        long expected=manifest.getLong("bytes");String checksum=manifest.getString("sha256");
        if(expected<1||expected>MAX_COMPRESSED||file.length()!=expected||!checksum.matches("[a-f0-9]{64}")||!MessageDigest.isEqual(checksum.getBytes(StandardCharsets.US_ASCII),sha256(file).getBytes(StandardCharsets.US_ASCII)))throw new IOException("CATALOG_CHECKSUM");
        String session=store.beginSectionImport();Map<String,List<Category>> categories=new LinkedHashMap<>();Map<String,Integer> counts=new LinkedHashMap<>();Map<String,List<MediaEntry>> batches=new LinkedHashMap<>();
        for(String t:new String[]{"live","vod","series"}){categories.put(t,new ArrayList<>());counts.put(t,0);batches.put(t,new ArrayList<>());}
        boolean header=false,end=false,committed=false;long expanded=0;int total=0;
        try(InputStream in=new GZIPInputStream(new BufferedInputStream(new FileInputStream(file),65536),65536)){
            ByteArrayOutputStream line=new ByteArrayOutputStream();byte[] buf=new byte[65536];int n;
            while((n=in.read(buf))!=-1){StreamingJsonArray.checkCancelled();guard.run();expanded+=n;if(expanded>MAX_EXPANDED)throw new IOException("CATALOG_SIZE");
                for(int i=0;i<n;i++){
                    if(buf[i]!='\n'){if(line.size()>=1048576)throw new IOException("CATALOG_ROW_SIZE");line.write(buf[i]);continue;}
                    if(line.size()==0)throw new IOException("CATALOG_EMPTY_ROW");JSONObject row=new JSONObject(line.toString("UTF-8"));line.reset();String kind=row.getString("kind");
                    if(!header){if(!kind.equals("header")||row.getInt("schema")!=1||!manifest.getString("source_id").equals(row.getString("source_id"))||!manifest.getString("fingerprint").equals(row.getString("fingerprint")))throw new IOException("CATALOG_HEADER");header=true;continue;}
                    if(end)throw new IOException("CATALOG_TRAILING_DATA");
                    if(kind.equals("category")){String t=row.getString("type");if(!categories.containsKey(t))throw new IOException("CATALOG_SECTION");List<Category> cats=categories.get(t);if(cats.size()>=10000)throw new IOException("CATALOG_CATEGORIES");cats.add(new Category(row.getString("id"),row.getString("name"),t));}
                    else if(kind.equals("item")){
                        MediaEntry e=store.decodePackageEntry(row.getJSONObject("entry").toString());if(e==null||e.id==null||e.id.isEmpty()||!batches.containsKey(e.type)||!e.sourceId.isEmpty())throw new IOException("CATALOG_ITEM");
                        if(++total>500000)throw new IOException("CATALOG_ITEMS");for(String u:e.candidates)if(!u.startsWith("http://")&&!u.startsWith("https://"))throw new IOException("CATALOG_STREAM_URL");
                        List<MediaEntry> batch=batches.get(e.type);batch.add(e);counts.put(e.type,counts.get(e.type)+1);if(batch.size()>=240){store.importBatch(session,profile,e.type,batch);batch.clear();progress.update(total);}
                    }else if(kind.equals("end")){
                        for(String t:counts.keySet())if(row.getJSONObject("counts").getInt(t)!=counts.get(t)||manifest.getJSONObject("counts").getInt(t)!=counts.get(t)||row.getJSONObject("categories").getInt(t)!=categories.get(t).size())throw new IOException("CATALOG_COUNTS");end=true;
                    }else throw new IOException("CATALOG_RECORD");
                }
            }
            if(!header||!end||line.size()!=0)throw new IOException("CATALOG_TRUNCATED");
            for(String t:batches.keySet())if(!batches.get(t).isEmpty())store.importBatch(session,profile,t,batches.get(t));
            guard.run();store.finishCatalogImport(session,profile,counts,categories,guard);committed=true;progress.update(total);return total;
        }finally{if(!committed)store.abortSectionImport(session);}
    }
}
