package com.nenotv.player.model;

import java.io.Serializable;
import java.util.ArrayList;

public class MediaEntry implements Serializable {
    /** Fixed so that stored reminders (ReminderStore) survive new fields in later versions. */
    private static final long serialVersionUID = 1L;
    public String id="", streamId="", seriesId="", name="Untitled", logo="", backdrop="", categoryId="", type="live", rating="", year="", plot="", extension="", directSource="", url="", group="", tvgId="", tvgName="", seriesTitle="", tmdbId="", imdbId="", sourceId="", sourceName="";
    public int season=0, episode=0, catchupDays=0;
    /** Step 3: channel number from the provider (M3U tvg-chno, Xtream "num"); 0 = none, then the list position is used. */
    public int number=0;
    public boolean catchup=false;
    /** P3: M3U catch-up kind (default, append, shift, flussonic, xc) and catchup-source template. */
    public String catchupType="", catchupSource="";
    public ArrayList<String> candidates = new ArrayList<>();
    public String uniqueKey(){
        String legacy=type+":"+(id.isEmpty()?url:id);
        // Untagged primary entries retain existing favorites and playback progress.
        return sourceId==null||sourceId.isEmpty()?legacy:"source:"+sourceId.length()+":"+sourceId+"|"+legacy;
    }
    public String displayYear(){if(year==null)return "";java.util.regex.Matcher m=java.util.regex.Pattern.compile("(?:19|20)\\d{2}").matcher(year);return m.find()?m.group():year.trim();}
    public static String formatRating(String value){
        if(value==null)return "";
        try{double d=Double.parseDouble(value.trim().replace(",","."));
            if(!Double.isFinite(d)||d<=0||d>10)return "";
            return new java.text.DecimalFormat("0.#").format(d);
        }catch(Exception ignored){return "";}
    }

    public String meta(){
        StringBuilder s=new StringBuilder();
        String dy=displayYear();if(!dy.isEmpty()) s.append(dy);
        if(!formatRating(rating).isEmpty()){ if(s.length()>0)s.append(" · "); s.append("★ ").append(formatRating(rating)); }
        if(type.equals("episode")){ if(s.length()>0)s.append(" · "); s.append("S").append(season).append("E").append(episode); }
        if(!plot.isEmpty()){ if(s.length()>0)s.append("\n"); s.append(plot); }
        return s.toString();
    }
}
