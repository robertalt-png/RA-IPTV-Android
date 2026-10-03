package com.nenotv.player.model;

import java.io.Serializable;

public class MediaDetails implements Serializable {
    public String title="", year="", genre="", duration="", director="", cast="", country="", plot="";
    public String rating="", imdbRating="", tmdbRating="", imdbId="", tmdbId="", backdrop="", poster="", trailer="";
    public boolean hasImdb(){ return imdbId!=null && imdbId.trim().startsWith("tt"); }
    public boolean hasTmdb(){ return tmdbId!=null && !tmdbId.trim().isEmpty(); }
    public String scoreLabel(){
        String imdb=MediaEntry.formatRating(imdbRating),tmdb=MediaEntry.formatRating(tmdbRating),generic=MediaEntry.formatRating(rating);
        if(!imdb.isEmpty())return "IMDb "+imdb;
        if(!tmdb.isEmpty())return "TMDb "+tmdb;
        return generic;
    }
}
