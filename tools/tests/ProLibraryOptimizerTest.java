import com.nenotv.player.model.MediaEntry;
import com.nenotv.player.proextras.ProLibraryOptimizer;
import java.util.Arrays;
import java.util.List;

public final class ProLibraryOptimizerTest {
    static MediaEntry item(String id,String name,String group){MediaEntry e=new MediaEntry();e.id=id;e.name=name;e.group=group;return e;}
    static void first(MediaEntry expected,MediaEntry... entries){
        List<MediaEntry> sorted=ProLibraryOptimizer.optimize(Arrays.asList(entries),"live","nl");
        if(sorted.get(0)!=expected)throw new AssertionError("Wrong first channel: "+sorted.get(0).name);
    }
    public static void main(String[] args){
        MediaEntry sd=item("sd","A SD","NL | TV"),hd=item("hd","Z HD","NL | TV"),fhd=item("fhd","Z FHD","NL | TV");
        MediaEntry uhd=item("uhd","Z UHD","NL | TV"),fourK=item("4k","Z 4K","NL | TV"),npo=item("npo","Z NPO 1 HD","NL | TV");
        MediaEntry radio=item("radio","A Radio","NL | TV"),fm=item("fm","A FM","NL | TV"),en=item("en","A HD","EN | TV");
        first(hd,sd,hd);first(fhd,hd,fhd);first(uhd,fhd,uhd);first(fourK,sd,fourK);first(npo,uhd,npo);
        first(sd,radio,sd);first(sd,fm,sd);first(hd,en,hd);
        MediaEntry notHd=item("inside","Z UHDream","NL | TV");first(sd,notHd,sd);
        if(ProLibraryOptimizer.optimize(Arrays.asList(hd,hd,null),"live","nl").size()!=1)throw new AssertionError("Deduplication failed");
        if(!ProLibraryOptimizer.optimize(null,"live","nl").isEmpty())throw new AssertionError("Null input failed");
        System.out.println("11 Pro language/quality/radio optimizer checks passed");
    }
}
