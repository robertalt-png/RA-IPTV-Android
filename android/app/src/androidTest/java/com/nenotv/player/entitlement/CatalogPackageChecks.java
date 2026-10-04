package com.nenotv.player.entitlement;
import android.content.Context;
import com.nenotv.player.storage.SearchIndexStore;
import com.nenotv.player.model.MediaEntry;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;
public final class CatalogPackageChecks {
    static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
    static JSONObject entry(String id,String type)throws Exception{return new JSONObject().put("id",id).put("name","NL Package "+id).put("type",type).put("categoryId","c").put("candidates",new JSONArray().put("https://provider.example/stream/"+id));}
    static byte[] gzip(String text)throws Exception{ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(GZIPOutputStream out=new GZIPOutputStream(bytes)){out.write(text.getBytes("UTF-8"));}return bytes.toByteArray();}
    static JSONObject manifest(File f,int count)throws Exception{return new JSONObject().put("schema",1).put("source_id","s").put("fingerprint","f").put("bytes",f.length()).put("sha256",CatalogPackageImporter.sha256(f)).put("counts",new JSONObject().put("live",count).put("vod",0).put("series",0));}
    static String header()throws Exception{return new JSONObject().put("kind","header").put("schema",1).put("source_id","s").put("fingerprint","f").toString()+"\n";}
    static String end(int n)throws Exception{return new JSONObject().put("kind","end").put("counts",new JSONObject().put("live",n).put("vod",0).put("series",0)).put("categories",new JSONObject().put("live",1).put("vod",0).put("series",0))+"\n";}
    public static void run(Context c,Context fixtureContext)throws Exception {
        File f=File.createTempFile("catalog-qa", ".gz",c.getCacheDir());String key="package-qa-"+System.nanoTime();SearchIndexStore index=new SearchIndexStore(c);
        try{
            try(InputStream in=fixtureContext.getAssets().open("catalog-fixture.gz");OutputStream out=new FileOutputStream(f)){byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}
            ByteArrayOutputStream meta=new ByteArrayOutputStream();try(InputStream in=fixtureContext.getAssets().open("catalog-fixture.json")){byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1)meta.write(b,0,n);}
            JSONObject serverManifest=new JSONObject(meta.toString("UTF-8"));
            check(CatalogPackageImporter.importFile(f,serverManifest,index,key,n->{},()->{})==3,"PHP to Android package");
            check(index.countSection(key,"live")==1&&index.countSection(key,"vod")==1&&index.countSection(key,"series")==1,"PHP full catalog sections");
            String category=new JSONObject().put("kind","category").put("type","live").put("id","c").put("name","NL")+"\n";
            StringBuilder items=new StringBuilder();for(int i=0;i<1000;i++)items.append(new JSONObject().put("kind","item").put("entry",entry("id"+i,"live"))).append('\n');
            // Concatenated gzip members exactly match the server's staged single download.
            try(OutputStream out=new FileOutputStream(f)){out.write(gzip(header()+category));out.write(gzip(items.toString()));out.write(gzip(end(1000)));}
            JSONObject m=manifest(f,1000);check(CatalogPackageImporter.importFile(f,m,index,key,n->{},()->{})==1000,"package import count");
            check(index.countSection(key,"live")==1000&&index.isComplete(key,"vod")&&index.isComplete(key,"series"),"all sections committed including empty");check(index.cachedCategories(key,"live").size()==1,"categories committed");
            m.put("sha256",String.join("",Collections.nCopies(64,"0")));try{CatalogPackageImporter.importFile(f,m,index,key,n->{},()->{});throw new AssertionError("checksum accepted");}catch(IOException expected){}check(index.countSection(key,"live")==1000,"checksum preserved old library");
            try(OutputStream out=new FileOutputStream(f)){out.write(gzip(header()+category+new JSONObject().put("kind","item").put("entry",entry("new","live"))+"\n"+end(2)));}
            try{CatalogPackageImporter.importFile(f,manifest(f,2),index,key,n->{},()->{});throw new AssertionError("wrong counts accepted");}catch(IOException expected){}check(index.countSection(key,"live")==1000,"bad counts preserved library");
            try(OutputStream out=new FileOutputStream(f)){out.write(gzip(header()+category+new JSONObject().put("kind","item").put("entry",entry("new","live"))+"\n"+end(1)));}
            try{CatalogPackageImporter.importFile(f,manifest(f,1),index,key,n->{},()->{throw new IllegalStateException("account changed");});throw new AssertionError("account switch accepted");}catch(IllegalStateException expected){}check(index.countSection(key,"live")==1000,"account switch preserved library");
            JSONObject changed=manifest(f,1).put("source_id","other");try{CatalogPackageImporter.importFile(f,changed,index,key,n->{},()->{});throw new AssertionError("wrong source accepted");}catch(IOException expected){}
            CatalogPackageImporter.importFile(f,manifest(f,1),index,key,n->{},()->{});check(index.countSection(key,"live")==1,"removed titles cleaned atomically");
            // Device-side provider maintenance remains a supported replacement route.
            MediaEntry e=new MediaEntry();e.id="provider-new";e.type="live";e.name="New provider title";index.replaceSection(key,"live",Collections.singletonList(e));check(index.countSection(key,"live")==1&&index.sectionPage(key,"live",0,2,"name","").get(0).id.equals("provider-new"),"subsequent provider maintenance");
        }finally{index.replaceSection(key,"live",Collections.emptyList());index.replaceSection(key,"vod",Collections.emptyList());index.replaceSection(key,"series",Collections.emptyList());index.close();f.delete();}
    }
}
