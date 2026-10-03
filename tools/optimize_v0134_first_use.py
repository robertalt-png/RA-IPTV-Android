from pathlib import Path

root=Path(".")
store=root/"app/src/main/java/com/robertalt/raiptv/storage/SearchIndexStore.java"
main=root/"app/src/main/java/com/robertalt/raiptv/MainActivity.java"
instr=root/"app/src/androidTest/java/com/nenotv/player/ImportInstrumentation.java"
for p in (store,main,instr):
    if not p.exists(): raise SystemExit(f"missing {p}")

s=store.read_text(encoding="utf-8")
marker='''    public synchronized List<Category> cachedCategories(String profile,String section){
        ArrayList<Category> out=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT category_id,name FROM category_cache WHERE profile=? AND section=? ORDER BY rowid ASC",new String[]{profile,section})){
            while(c.moveToNext())out.add(new Category(c.getString(0),c.getString(1),section));
        }catch(Exception ignored){}
        return out;
    }
'''
if marker not in s: raise SystemExit("cachedCategories marker missing")
if "categoriesFresh(" not in s:
    addition=marker+'''
    public synchronized boolean categoriesFresh(String profile,String section,long ttlMs){
        try(Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(*),MAX(updated) FROM category_cache WHERE profile=? AND section=?",new String[]{profile,section})){
            if(!c.moveToFirst()||c.getInt(0)<=0)return false;
            long updated=c.isNull(1)?0L:c.getLong(1);
            return updated>0L && System.currentTimeMillis()-updated<Math.max(1L,ttlMs);
        }catch(Exception ignored){return false;}
    }
'''
    s=s.replace(marker,addition)
store.write_text(s,encoding="utf-8")

m=main.read_text(encoding="utf-8")
old='''                    for(String type:baseTypes){
                        List<Category> cats=new ArrayList<>(indexProvider.categories(type));
                        searchIndex.replaceCategories(key,type,cats);
                        catMap.put(type,cats);globalTotal+=cats.size();
                    }'''
new='''                    for(String type:baseTypes){
                        List<Category> cats=searchIndex.cachedCategories(key,type);
                        boolean categoryCacheFresh=searchIndex.categoriesFresh(key,type,30L*60L*1000L);
                        if(cats.isEmpty()||migration||requestedForce||!categoryCacheFresh){
                            try{
                                List<Category> remoteCats=new ArrayList<>(indexProvider.categories(type));
                                searchIndex.replaceCategories(key,type,remoteCats);
                                cats=remoteCats;
                            }catch(Exception categoryError){
                                if(cats.isEmpty())throw categoryError;
                            }
                        }
                        catMap.put(type,cats);globalTotal+=cats.size();
                    }'''
if old not in m: raise SystemExit("category prefetch marker missing")
m=m.replace(old,new)
m=m.replace('                    final int FETCH_WINDOW=3;\n','')
main.write_text(m,encoding="utf-8")

i=instr.read_text(encoding="utf-8")
if "import com.nenotv.player.model.Category;" not in i:
    i=i.replace("import com.nenotv.player.model.MediaEntry;","import com.nenotv.player.model.MediaEntry;\nimport com.nenotv.player.model.Category;")
anchor='''            store.upsert(PROFILE, Collections.singletonList(entry("movie", "vod")));
'''
if anchor not in i: raise SystemExit("instrumentation anchor missing")
if "QA category" not in i:
    test='''            ArrayList<Category> qaCategories = new ArrayList<>();
            qaCategories.add(new Category("qa-cat","QA category","live"));
            store.replaceCategories(PROFILE,"live",qaCategories);
            require(store.cachedCategories(PROFILE,"live").size()==1,"Category cache missing");
            require(store.categoriesFresh(PROFILE,"live",30L*60L*1000L),"Category cache not fresh");
'''
    i=i.replace(anchor,anchor+test)
instr.write_text(i,encoding="utf-8")
print("Applied v0.13.4 persisted category fast-start optimization")
