from pathlib import Path
root=Path("source/RA_IPTV_Android_v0.1")
p=root/"app/src/main/java/com/robertalt/raiptv/MainActivity.java"
s=p.read_text()

old='''    void renderSingleShelf(String name,List<MediaEntry>items){showBrowse();browseContainer.removeAllViews();addShelf(name,items);if(items==null||items.isEmpty())addEmptyBrowse(T("no_titles_selection"));}'''
new='''    void renderSingleShelf(String name,List<MediaEntry>items){List<MediaEntry>x=items==null?Collections.emptyList():items;showMediaGrid(false);all=new ArrayList<>(x);gridAdapter.set(x,false);if(!x.isEmpty())grid.setSelection(0);}'''
assert old in s
s=s.replace(old,new,1)

# Make the genre result path explicitly use the same vertical library grid for films and series.
old='''runOnUiThread(()->{if(!current(token))return;renderSingleShelf(T("genre")+" · "+genreLabel,x);if(!x.isEmpty())previewAuto(x.get(0));busy(false,x.size()+" "+T("titles")+" · "+genreLabel);});'''
new='''runOnUiThread(()->{if(!current(token))return;renderSingleShelf(T("genre")+" · "+genreLabel,x);if(!x.isEmpty())previewAuto(x.get(0));busy(false,x.size()+" "+T("titles")+" · "+genreLabel+" · "+T("fully_scrollable"));});'''
assert old in s
s=s.replace(old,new,1)

p.write_text(s)

p=root/"app/build.gradle"
s=p.read_text().replace("versionCode 18","versionCode 19").replace("versionName '0.5.9'","versionName '0.6.0'")
p.write_text(s)

assert "showMediaGrid(false);all=new ArrayList<>(x);gridAdapter.set(x,false)" in (root/"app/src/main/java/com/robertalt/raiptv/MainActivity.java").read_text()
assert "versionName '0.6.0'" in (root/"app/build.gradle").read_text()
print("v0.6.0 vertical library grid applied")
