from pathlib import Path

root=Path(".")
main=root/"app/src/main/java/com/robertalt/raiptv/MainActivity.java"
if not main.exists(): raise SystemExit("missing MainActivity.java")

m=main.read_text(encoding="utf-8")
old='''    void warmCategories(){if(provider==null||profile==null||profile.type!=Profile.Type.XTREAM)return;final String key=profileKey();for(String s:new String[]{"live","vod","series"})exec.execute(()->{try{List<Category> c=new ArrayList<>(provider.categories(s));searchIndex.replaceCategories(key,s,c);}catch(Exception ignored){}});}'''
new='''    void warmCategories(){if(provider==null||profile==null||profile.type!=Profile.Type.XTREAM)return;final String key=profileKey();for(String s:new String[]{"live","vod","series"})exec.execute(()->{try{List<Category> cached=searchIndex.cachedCategories(key,s);if(!cached.isEmpty()&&searchIndex.categoriesFresh(key,s,30L*60L*1000L))return;List<Category> c=new ArrayList<>(provider.categories(s));searchIndex.replaceCategories(key,s,c);}catch(Exception ignored){}});}'''
if old not in m: raise SystemExit("warmCategories marker missing")
m=m.replace(old,new,1)
main.write_text(m,encoding="utf-8")
print("Applied v0.13.5 cold-start category network suppression")
