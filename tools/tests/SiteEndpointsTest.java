package com.nenotv.player;

public final class SiteEndpointsTest {
    public static void main(String[] args) {
        int checks=0;
        for(String language:new String[]{"nl","en","de","fr","",null}) {
            String anchor="nl".equals(language)?"nl":"de".equals(language)?"de":"en";
            String actual=SiteEndpoints.accountDeletionUrl(language);
            if(!actual.equals("https://sunnyiptv.com/delete-account/#"+anchor))throw new AssertionError("Deletion route: "+language);
            if(actual.contains("?"))throw new AssertionError("Account information in deletion URL");
            checks+=2;
        }
        if(!SiteEndpoints.isDemoUrl("https://nenotv.com/nenotv-demo.m3u"))throw new AssertionError("Legacy demo removed");
        if(!SiteEndpoints.isDemoUrl(SiteEndpoints.DEMO_URL))throw new AssertionError("Sunny demo removed");
        if(!SiteEndpoints.isPairingHost("nenotv.com")||SiteEndpoints.isPairingHost("sunnyiptv.com.evil.invalid"))throw new AssertionError("Pairing host compatibility");
        System.out.println("Site endpoints: "+(checks+3)+" checks passed");
    }
}
