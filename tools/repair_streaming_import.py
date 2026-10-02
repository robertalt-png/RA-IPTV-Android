from pathlib import Path
from repair_responsiveness import replace_once

RUNTIME = Path(__file__).resolve().parent / "runtime"


def provider_source(text):
    marker = '    private MediaEntry from(JSONObject x,String type){'
    text = replace_once(text, marker, (RUNTIME / "XtreamStreamMethods.java.txt").read_text(encoding="utf-8") + marker)
    old = '''            JSONArray rows=arr(a,cat);
            List<MediaEntry>o=new ArrayList<>(rows.length());
            for(int i=0;i<rows.length();i++)o.add(from(rows.getJSONObject(i),type));'''
    new = '''            List<MediaEntry>o=new ArrayList<>();
            com.nenotv.player.net.StreamingJsonArray.read(XtreamUrls.api(p.server,p.username,p.password,a,cat),json->o.add(from(new JSONObject(json.toString()),type)));'''
    return replace_once(text, old, new)


def store_source(text):
    text = replace_once(text, '    public synchronized void clearAll(){', (RUNTIME / "SectionImportMethods.java.txt").read_text(encoding="utf-8") + '    public synchronized void clearAll(){')
    text = replace_once(text, '    private void upsertInternal(SQLiteDatabase db,String profile,MediaEntry e){', '''    private void upsertInternal(SQLiteDatabase db,String profile,MediaEntry e){
        upsertInternal(db,"entries",null,profile,e);
    }
    private void upsertInternal(SQLiteDatabase db,String table,String session,String profile,MediaEntry e){''')
    text = replace_once(text, '            db.insertWithOnConflict("entries",null,v,SQLiteDatabase.CONFLICT_REPLACE);\n        }catch(Exception ignored){}', '''            if(session!=null){v.put("session",session);v.put("started",System.currentTimeMillis());}
            if(db.insertWithOnConflict(table,null,v,SQLiteDatabase.CONFLICT_REPLACE)<0)throw new IllegalStateException("cache_write_failed");
        }catch(Exception failure){throw new IllegalStateException("cache_write_failed",failure);}''')
    return text


def main_source(text):
    if "void showStreamingBanner(" in text:
        raise ValueError("Streaming import repair has already been applied")
    if text.count('                        boolean bulkDone=false;') != 1:
        raise ValueError("Expected exactly one bulk import block")
    if text.count('                        if(bulkDone)continue;\n') != 1:
        raise ValueError("Expected exactly one bulk import terminator")
    start = text.index('                        boolean bulkDone=false;')
    end_marker = '                        if(bulkDone)continue;\n'
    end = text.index(end_marker, start) + len(end_marker)
    text = text[:start] + (RUNTIME / "BulkImport.java.txt").read_text(encoding="utf-8") + text[end:]
    marker = '    void showIndexBanner(String type,int done,int cats,int titles){'
    text = replace_once(text, marker, '''    void showStreamingBanner(int titles){
        if(indexBanner==null||indexBannerText==null)return;
        indexBanner.setVisibility(View.VISIBLE);
        indexBannerText.setText(T("loading")+" · "+Math.max(0,titles)+" "+T("titles"));
        if(indexBannerProgress!=null)indexBannerProgress.setIndeterminate(true);
    }
''' + marker)
    return text


def repair_project(root):
    root = Path(root)
    java = root / "app/src/main/java/com/robertalt/raiptv"
    files = {
        java / "provider/XtreamProvider.java": provider_source,
        java / "storage/SearchIndexStore.java": store_source,
        java / "MainActivity.java": main_source,
    }
    # Validate all donor markers before writing any of these files.
    updated = {path: repair(path.read_text(encoding="utf-8")) for path, repair in files.items()}
    gradle = root / "app/build.gradle"
    config = gradle.read_text(encoding="utf-8")
    updated[gradle] = replace_once(config, 'dependencies {', "dependencies {\n    implementation 'com.google.code.gson:gson:2.13.2'")
    for path, text in updated.items():
        path.write_text(text, encoding="utf-8")
    (java / "net/StreamingJsonArray.java").write_text((RUNTIME / "StreamingJsonArray.java").read_text(encoding="utf-8"), encoding="utf-8")
