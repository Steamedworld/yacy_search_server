
import java.util.Date;
import java.util.Iterator;

import de.anomic.data.bookmarksDB;
import de.anomic.http.httpRequestHeader;
import de.anomic.kelondro.coding.DateFormatter;
import de.anomic.kelondro.coding.Digest;
import de.anomic.plasma.plasmaSwitchboard;
import de.anomic.server.serverObjects;
import de.anomic.server.serverSwitch;

public class all {
    public static serverObjects respond(final httpRequestHeader header, final serverObjects post, final serverSwitch<?> env) {
        // return variable that accumulates replacements
        final plasmaSwitchboard switchboard = (plasmaSwitchboard) env;
        final boolean isAdmin=switchboard.verifyAuthentication(header, true);
        final serverObjects prop = new serverObjects();
        
        Iterator<String> it;
        if(post != null && post.containsKey("tag")){
            it=switchboard.bookmarksDB.getBookmarksIterator(post.get("tag"), isAdmin);
        }else{
            it=switchboard.bookmarksDB.getBookmarksIterator(isAdmin);
        }
        
        // if an extended xml should be used
        final boolean extendedXML = (post != null && post.containsKey("extendedXML"));
        
        int count=0;
        bookmarksDB.Bookmark bookmark;
        Date date;
        while(it.hasNext()){
            bookmark=switchboard.bookmarksDB.getBookmark(it.next());
            prop.putXML("posts_"+count+"_url", bookmark.getUrl());
            prop.putXML("posts_"+count+"_title", bookmark.getTitle());
            prop.putXML("posts_"+count+"_description", bookmark.getDescription());
            prop.putXML("posts_"+count+"_md5", Digest.encodeMD5Hex(bookmark.getUrl()));
            date=new Date(bookmark.getTimeStamp());
            prop.putXML("posts_"+count+"_time", DateFormatter.formatISO8601(date));
            prop.putXML("posts_"+count+"_tags", bookmark.getTagsString().replaceAll(","," "));
            
            // additional XML tags
            prop.put("posts_"+count+"_isExtended",extendedXML ? "1" : "0");
            if (extendedXML) {
            	prop.put("posts_"+count+"_isExtended_private", Boolean.toString(!bookmark.getPublic()));
            }
            count++;
        }
        prop.put("posts", count);

        // return rewrite properties
        return prop;
    }
    
}
