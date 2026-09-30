from pathlib import Path

p=Path("app/src/main/java/com/robertalt/raiptv/core/XtreamUrls.java")
s=p.read_text()
old='public static String enc(String s){ return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8); }'
new='public static String enc(String s){ try{return URLEncoder.encode(s == null ? "" : s, "UTF-8");}catch(java.io.UnsupportedEncodingException e){throw new IllegalStateException(e);} }'
if old in s:
    s=s.replace(old,new)
elif new not in s:
    raise SystemExit("Unexpected XtreamUrls.enc implementation; refusing blind patch")
s=s.replace('import java.nio.charset.StandardCharsets;\n','')
p.write_text(s)
print("Applied Android 26+ Xtream URL encoding compatibility repair")
