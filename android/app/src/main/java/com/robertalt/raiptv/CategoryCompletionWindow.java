package com.nenotv.player;

public final class CategoryCompletionWindow {
    private final boolean[] done;
    private int committed;

    public CategoryCompletionWindow(int total,int resumeAt){
        done=new boolean[Math.max(0,total)];
        committed=Math.max(-1,resumeAt-1);
    }

    public synchronized int markDone(int index){
        if(index>=0&&index<done.length)done[index]=true;
        while(committed+1<done.length&&done[committed+1])committed++;
        return committed;
    }

    public synchronized int committedIndex(){return committed;}
}