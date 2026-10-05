package com.nenotv.player.cast;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

/**
 * LAN-only compatibility relay used only when direct Google Cast playback fails.
 * It keeps IPTV credentials on the local network, adds CORS for Cast, forwards
 * Range requests, and rewrites HLS playlists so nested playlists/segments also
 * pass through the relay. It does not transcode codecs.
 */
public final class CastRelayServer implements Closeable {
    private static final String UA="Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 SunnyIPTV/0.9.3";
    private final ServerSocket server;
    private final ExecutorService workers=Executors.newCachedThreadPool(r->{Thread t=new Thread(r,"nenotv-cast-relay");t.setDaemon(true);return t;});
    private final Thread acceptThread;
    private final String secret;
    private final String host;
    private volatile boolean closed=false;

    public CastRelayServer() throws IOException {
        host=findLanIpv4();
        if(host==null||host.isEmpty())throw new IOException("no_lan_address");
        secret=Long.toHexString(System.nanoTime())+Long.toHexString(new Random().nextLong());
        server=new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress("0.0.0.0",0));
        acceptThread=new Thread(this::acceptLoop,"nenotv-cast-relay-accept");
        acceptThread.setDaemon(true);acceptThread.start();
    }

    public String relayUrl(String upstream){
        String token=Base64.getUrlEncoder().withoutPadding().encodeToString(upstream.getBytes(StandardCharsets.UTF_8));
        return "http://"+host+":"+server.getLocalPort()+"/"+secret+"/p/"+token;
    }

    private void acceptLoop(){
        while(!closed){try{Socket s=server.accept();workers.execute(()->handle(s));}catch(IOException e){if(!closed)try{Thread.sleep(100);}catch(InterruptedException ignored){Thread.currentThread().interrupt();}}}
    }

    private void handle(Socket socket){
        try(Socket s=socket){
            s.setSoTimeout(30000);
            BufferedInputStream in=new BufferedInputStream(s.getInputStream());
            OutputStream out=new BufferedOutputStream(s.getOutputStream());
            String line=readLine(in);if(line==null||line.isEmpty())return;
            String[] first=line.split(" ");if(first.length<2)return;
            String method=first[0].toUpperCase(Locale.ROOT),path=first[1];
            LinkedHashMap<String,String> headers=new LinkedHashMap<>();
            while((line=readLine(in))!=null&&!line.isEmpty()){
                int k=line.indexOf(':');if(k>0)headers.put(line.substring(0,k).trim().toLowerCase(Locale.ROOT),line.substring(k+1).trim());
            }
            if("OPTIONS".equals(method)){writeSimple(out,204,"text/plain",new byte[0]);return;}
            String prefix="/"+secret+"/p/";
            if(!path.startsWith(prefix)){writeSimple(out,404,"text/plain","Not found".getBytes(StandardCharsets.UTF_8));return;}
            String token=path.substring(prefix.length());int q=token.indexOf('?');if(q>=0)token=token.substring(0,q);
            String upstream;
            try{upstream=new String(Base64.getUrlDecoder().decode(token),StandardCharsets.UTF_8);}catch(Exception e){writeSimple(out,400,"text/plain","Bad request".getBytes(StandardCharsets.UTF_8));return;}
            proxy(method,upstream,headers,out);
        }catch(Exception ignored){}
    }

    private void proxy(String method,String raw,Map<String,String> request,OutputStream out)throws IOException{
        HttpURLConnection c=null;
        try{
            URL u=new URL(raw);c=(HttpURLConnection)u.openConnection();
            c.setConnectTimeout(12000);c.setReadTimeout(30000);c.setInstanceFollowRedirects(true);c.setUseCaches(false);
            c.setRequestProperty("User-Agent",UA);c.setRequestProperty("Accept","*/*");c.setRequestProperty("Accept-Encoding","identity");c.setRequestProperty("Connection","keep-alive");
            String range=request.get("range");if(range!=null&&!range.isEmpty())c.setRequestProperty("Range",range);
            c.setRequestMethod("GET");
            int code=c.getResponseCode();
            InputStream body=code>=200&&code<400?c.getInputStream():c.getErrorStream();
            String ct=cleanContentType(c.getContentType());
            boolean playlist=isPlaylist(raw,ct);
            if(playlist){
                byte[] data=readLimited(body,4*1024*1024);
                String text=new String(data,StandardCharsets.UTF_8);
                String rewritten=rewritePlaylist(text,u).replace("\r\n","\n");
                byte[] bytes=rewritten.getBytes(StandardCharsets.UTF_8);
                writeHeaders(out,200,"application/vnd.apple.mpegurl",(long)bytes.length,null,true);out.write(bytes);out.flush();return;
            }
            long len=c.getContentLengthLong();String cr=c.getHeaderField("Content-Range");
            int sendCode=code==206?206:(code>=200&&code<300?200:code);
            writeHeaders(out,sendCode,ct==null||ct.isEmpty()?guessType(raw):ct,len>=0?len:null,cr,true);
            if(!"HEAD".equals(method)&&body!=null){byte[] buf=new byte[64*1024];int n;while((n=body.read(buf))>=0){out.write(buf,0,n);out.flush();}}
        }finally{if(c!=null)c.disconnect();}
    }

    private String rewritePlaylist(String text,URL base){
        StringBuilder out=new StringBuilder();Pattern uriAttr=Pattern.compile("URI=\\\"([^\\\"]+)\\\"");
        for(String line:text.split("\\r?\\n",-1)){
            String x=line;
            Matcher m=uriAttr.matcher(x);StringBuffer sb=new StringBuffer();
            while(m.find()){try{String abs=new URL(base,m.group(1)).toString();m.appendReplacement(sb,"URI=\\\""+Matcher.quoteReplacement(relayUrl(abs))+"\\\"");}catch(Exception e){m.appendReplacement(sb,Matcher.quoteReplacement(m.group(0)));}}
            m.appendTail(sb);x=sb.toString();
            String t=x.trim();
            if(!t.isEmpty()&&!t.startsWith("#")){try{x=relayUrl(new URL(base,t).toString());}catch(Exception ignored){}}
            out.append(x).append('\n');
        }
        return out.toString();
    }

    private static boolean isPlaylist(String url,String ct){String x=(url+" "+(ct==null?"":ct)).toLowerCase(Locale.ROOT);return x.contains(".m3u8")||x.contains("mpegurl")||x.contains("vnd.apple");}
    private static String guessType(String u){String x=u.toLowerCase(Locale.ROOT);if(x.contains(".m3u8"))return "application/vnd.apple.mpegurl";if(x.contains(".ts"))return "video/mp2t";if(x.contains(".mp4")||x.contains(".m4v"))return "video/mp4";if(x.contains(".aac"))return "audio/aac";return "application/octet-stream";}
    private static String cleanContentType(String s){if(s==null)return "";int i=s.indexOf(';');return (i>=0?s.substring(0,i):s).trim();}

    private void writeSimple(OutputStream out,int code,String type,byte[] body)throws IOException{writeHeaders(out,code,type,(long)body.length,null,true);out.write(body);out.flush();}
    private void writeHeaders(OutputStream out,int code,String type,Long length,String contentRange,boolean cors)throws IOException{
        String reason=code==200?"OK":code==204?"No Content":code==206?"Partial Content":code==400?"Bad Request":code==404?"Not Found":"Error";
        StringBuilder h=new StringBuilder("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n");
        h.append("Content-Type: ").append(type==null||type.isEmpty()?"application/octet-stream":type).append("\r\n");
        if(length!=null)h.append("Content-Length: ").append(length).append("\r\n");
        if(contentRange!=null&&!contentRange.isEmpty())h.append("Content-Range: ").append(contentRange).append("\r\n");
        h.append("Accept-Ranges: bytes\r\nCache-Control: no-cache\r\nConnection: close\r\n");
        if(cors)h.append("Access-Control-Allow-Origin: *\r\nAccess-Control-Allow-Headers: Range, Content-Type, Accept, Origin\r\nAccess-Control-Expose-Headers: Content-Length, Content-Range, Accept-Ranges\r\n");
        h.append("\r\n");out.write(h.toString().getBytes(StandardCharsets.ISO_8859_1));out.flush();
    }
    private static byte[] readLimited(InputStream in,int max)throws IOException{if(in==null)return new byte[0];ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] buf=new byte[8192];int n;while((n=in.read(buf))>=0){b.write(buf,0,n);if(b.size()>max)throw new IOException("playlist_too_large");}return b.toByteArray();}
    private static String readLine(InputStream in)throws IOException{ByteArrayOutputStream b=new ByteArrayOutputStream();int c;boolean any=false;while((c=in.read())!=-1){any=true;if(c=='\n')break;if(c!='\r')b.write(c);if(b.size()>16384)throw new IOException("header_too_large");}return !any&&b.size()==0?null:b.toString(StandardCharsets.ISO_8859_1.name());}
    private static String findLanIpv4()throws SocketException{Enumeration<NetworkInterface> ns=NetworkInterface.getNetworkInterfaces();while(ns.hasMoreElements()){NetworkInterface n=ns.nextElement();if(!n.isUp()||n.isLoopback())continue;Enumeration<InetAddress> as=n.getInetAddresses();while(as.hasMoreElements()){InetAddress a=as.nextElement();if(a instanceof Inet4Address&&!a.isLoopbackAddress()&&a.isSiteLocalAddress())return a.getHostAddress();}}return null;}
    @Override public void close(){closed=true;try{server.close();}catch(Exception ignored){}workers.shutdownNow();}
}
