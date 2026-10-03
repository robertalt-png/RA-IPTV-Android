package com.nenotv.player.model;
public class Category {
    public final String id, name, type;
    public Category(String id, String name, String type) { this.id=id; this.name=name; this.type=type; }
    @Override public String toString(){ return com.nenotv.player.DisplayText.category(name); }
}
