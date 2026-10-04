package com.nenotv.player.provider;
import com.nenotv.player.model.*;
import com.nenotv.player.storage.SearchIndexStore;
import java.util.*;
/** A packaged M3U starts from the local index; authenticate switches to provider maintenance. */
public final class CatalogM3uProvider extends M3uProvider {
    private final SearchIndexStore index;private final String key;private volatile boolean cached=true;
    public CatalogM3uProvider(Profile p,String language,SearchIndexStore index,String key){super(p,language);this.index=index;this.key=key;}
    @Override public void authenticate()throws Exception{super.authenticate();cached=false;}
    @Override public List<Category> categories(String type){return cached?index.cachedCategories(key,type):super.categories(type);}
    @Override public List<MediaEntry> items(String type,String category){
        if(!cached)return super.items(type,category);
        List<MediaEntry> result=new ArrayList<>();int offset=0;
        while(true){List<MediaEntry> page=index.sectionPage(key,type,offset,240);for(MediaEntry e:page)if(category==null||category.isEmpty()||category.equals("all")||category.equals(e.group)||category.equals(e.categoryId))result.add(e);if(page.size()<240)break;offset+=page.size();}
        return result;
    }
}
