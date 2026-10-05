package com.nenotv.player;

public final class ExtraPrivacyPolicyTest {
    public static void main(String[] args) {
        int checks=0;
        for(String group:new String[]{null,"","unknown","under_13","13_plus","adult","13_PLUS"}) {
            for(boolean child:new boolean[]{false,true}) {
                boolean expected="13_plus".equals(group)&&!child;
                if(ExtraPrivacyPolicy.allowsSdk(group,child)!=expected)throw new AssertionError("Privacy policy failed: "+group+"/"+child);
                checks++;
            }
        }
        System.out.println("Extra privacy policy: "+checks+" checks passed");
    }
}
