package dev.busung.s25uroot.d2.setup;

import dev.experimental.rmgu.d2.D2Protocol;
import org.junit.Test;
import java.io.*;
import java.util.*;
import static org.junit.Assert.*;

public class PackageTrustTest {
    private static final String PLATFORM="11".repeat(120),OWN="22".repeat(120),FOREIGN="33".repeat(120);
    /** Independent wire fixture uses the AOSP ABX v0 byte protocol and inline string definitions. */
    private byte[] reference()throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);
        out.write(new byte[]{65,66,88,0,0x10,0x32});out.writeShort(65535);out.writeUTF("packages");
        out.writeByte(0x33);out.writeShort(0);out.writeByte(0x11);return bytes.toByteArray();
    }
    private AbxDocument.Element element(String name){return new AbxDocument.Element(name);}
    private void str(AbxDocument.Element e,String name,String value){e.attributes.add(new AbxDocument.Token(15,2,name,value));}
    private AbxDocument.Element cert(int index,String key,Integer flags){AbxDocument.Element c=element("cert");c.intAttr("index",index);if(key!=null)str(c,"key",key);if(flags!=null)c.intAttr("flags",flags);return c;}
    private AbxDocument.Element sig(int index,String key){AbxDocument.Element s=element("sigs");s.intAttr("count",1);s.contents.add(cert(index,key,null));return s;}
    private byte[] fixture(boolean past)throws Exception {
        AbxDocument d=AbxDocument.decode(reference());
        for(int i=0;i<20;i++){AbxDocument.Element p=element("package");str(p,"name",i==1?D2Protocol.CONTROLLER:"example.p"+i);p.intAttr("userId",10000+i);p.contents.add(sig(i==1?1:i==2?2:0,i==0?PLATFORM:i==1?OWN:i==2?FOREIGN:null));d.root.contents.add(p);}
        AbxDocument.Element user=element("shared-user");str(user,"name","android.uid.system");user.intAttr("userId",1000);
        AbxDocument.Element signatures=sig(0,null);
        if(past){AbxDocument.Element old=element("pastSigs");old.intAttr("count",1);old.contents.add(cert(2,null,7));signatures.contents.add(old);}
        user.contents.add(signatures);d.root.contents.add(user);
        return d.encode();
    }
    private void refused(byte[] bytes,String key)throws Exception {try{new PackageTrust(bytes).add(key);fail("Mutation should be refused");}catch(IOException expected){} }
    @Test public void referenceWireRoundTripIsIdentical()throws Exception {assertArrayEquals(reference(),AbxDocument.decode(reference()).encode());}
    @Test public void addsTwoIndexOnlyReferencesAndKeepsForeignHistory()throws Exception {
        PackageTrust before=new PackageTrust(fixture(true));byte[] patched=before.add(OWN);PackageTrust after=new PackageTrust(patched);
        assertEquals(2,after.ownedCount(OWN));assertEquals(1,after.ownedCount(FOREIGN));assertEquals(before.table,after.table);
        for(AbxDocument.Element c:after.past.children("cert"))if(c.attr("index").integer()==1){assertEquals(2,c.attr("flags").integer());assertEquals(2,c.attributes.size());}
    }
    @Test public void createsMissingHistoryAndCleanupRemovesOnlyOwnedReferences()throws Exception {
        PackageTrust after=new PackageTrust(new PackageTrust(fixture(false)).add(OWN));assertEquals(2,after.ownedCount(OWN));
        PackageTrust removed=new PackageTrust(after.remove(OWN));assertEquals(0,removed.ownedCount(OWN));assertEquals(20,removed.packages);
    }
    @Test public void cleanupPreservesForeignFlagsAndIndexTable()throws Exception {
        PackageTrust after=new PackageTrust(new PackageTrust(fixture(true)).add(OWN));
        PackageTrust removed=new PackageTrust(after.remove(OWN));assertEquals(after.table,removed.table);assertEquals(1,removed.ownedCount(FOREIGN));assertEquals(7,removed.past.children("cert").get(0).attr("flags").integer());
    }
    @Test public void repeatedPreparationIsRefused()throws Exception {refused(new PackageTrust(fixture(true)).add(OWN),OWN);}
    @Test public void wrongControllerCertificateIsRefused()throws Exception {refused(fixture(true),FOREIGN);}
    @Test public void removingPlatformCertificateIsRefused()throws Exception {try{new PackageTrust(fixture(true)).remove(PLATFORM);fail();}catch(IOException expected){} }
    @Test public void tooFewPackagesAreRefused()throws Exception {AbxDocument d=AbxDocument.decode(fixture(true));d.root.contents.remove(0);try{new PackageTrust(d.encode());fail();}catch(IOException expected){} }
    @Test public void multipleHistoryBlocksAreRefused()throws Exception {PackageTrust t=new PackageTrust(fixture(true));AbxDocument.Element p=element("pastSigs");p.intAttr("count",0);t.sigs.contents.add(p);try{new PackageTrust(t.doc.encode());fail();}catch(IOException expected){} }
    @Test public void incorrectHistoryCountIsRefused()throws Exception {PackageTrust t=new PackageTrust(fixture(true));t.past.attr("count").integer(2);try{new PackageTrust(t.doc.encode());fail();}catch(IOException expected){} }
    @Test public void unknownAbxVersionAndTruncationAreRefused()throws Exception {byte[] b=fixture(true);b[3]=1;try{AbxDocument.decode(b);fail();}catch(IOException expected){}try{AbxDocument.decode(Arrays.copyOf(fixture(true),10));fail();}catch(IOException expected){} }
    @Test public void typedUnknownPackageAttributesArePreserved()throws Exception {
        AbxDocument d=AbxDocument.decode(fixture(true));AbxDocument.Element p=d.root.children("package").get(0);
        p.attributes.add(new AbxDocument.Token(15,7,"hex_flags",new byte[]{0,0,0,15}));
        p.attributes.add(new AbxDocument.Token(15,11,"double_bits",new byte[]{127,(byte)248,0,0,0,0,0,1}));
        p.attributes.add(new AbxDocument.Token(15,12,"enabled",null));str(p,"unicode_label","日本\u0000😀");
        byte[] original=d.encode();PackageTrust t=new PackageTrust(original);PackageTrust after=new PackageTrust(t.add(OWN));
        AbxDocument.Element resulting=after.doc.root.children("package").get(0);
        assertEquals(7,resulting.attr("hex_flags").type);assertArrayEquals((byte[])p.attr("double_bits").value,(byte[])resulting.attr("double_bits").value);assertEquals("日本\u0000😀",resulting.text("unicode_label"));
    }
    @Test public void missingControllerOrSystemUidIsRefused()throws Exception {
        AbxDocument d=AbxDocument.decode(fixture(true));d.root.children("package").get(1).attr("name").value="other";refused(d.encode(),OWN);
        d=AbxDocument.decode(fixture(true));d.root.contents.remove(d.root.children("shared-user").get(0));try{new PackageTrust(d.encode());fail();}catch(IOException expected){}
    }
    @Test public void unexpectedSystemUidIsRefused()throws Exception {PackageTrust t=new PackageTrust(fixture(true));t.user.attr("userId").integer(1001);try{new PackageTrust(t.doc.encode());fail();}catch(IOException expected){} }
    @Test public void rawInputBytesAreNeverModifiedByTransformation()throws Exception {byte[] b=fixture(true),copy=b.clone();new PackageTrust(b).add(OWN);assertArrayEquals(copy,b);}
    @Test public void removingAbsentKeyDoesNotChangeBytes()throws Exception {byte[] b=fixture(true);assertArrayEquals(b,new PackageTrust(b).remove(OWN));}
    @Test public void binaryCertificateKeysUseExistingPmsIndexes()throws Exception {
        AbxDocument d=AbxDocument.decode(fixture(true));
        for(AbxDocument.Element p:d.root.children("package"))for(AbxDocument.Element s:p.children("sigs"))for(AbxDocument.Element c:s.children("cert")) {
            AbxDocument.Token key=null;for(AbxDocument.Token t:c.attributes)if(t.name.equals("key"))key=t;
            if(key!=null){c.attributes.remove(key);c.attributes.add(new AbxDocument.Token(15,AbxDocument.BYTES_HEX,"key",AbxDocument.unhex(key.text().toLowerCase(Locale.ROOT))));}
        }
        PackageTrust before=new PackageTrust(d.encode()),after=new PackageTrust(before.add(OWN));
        assertEquals(before.table,after.table);assertEquals(2,after.ownedCount(OWN));assertEquals(AbxDocument.BYTES_HEX,after.doc.root.children("package").get(1).children("sigs").get(0).children("cert").get(0).attr("key").type);
    }
    @Test public void controllerDeclaredCountMismatchIsRefused()throws Exception {AbxDocument d=AbxDocument.decode(fixture(true));d.root.children("package").get(1).children("sigs").get(0).attr("count").integer(0);refused(d.encode(),OWN);}
    @Test public void certificateIndexesMayContainHolesWithoutRenumbering()throws Exception {
        AbxDocument d=AbxDocument.decode(fixture(true));
        for(AbxDocument.Element p:d.root.children("package"))for(AbxDocument.Element c:p.children("sigs").get(0).children("cert"))if(c.attr("index").integer()==1)c.attr("index").integer(8);
        PackageTrust t=new PackageTrust(d.encode()); assertEquals(8,t.controllerIndex(OWN));
        PackageTrust after=new PackageTrust(t.add(OWN));assertEquals(8,after.past.children("cert").get(1).attr("index").integer());assertNull(after.table.get(1));
    }
    @Test public void conflictingCertificateDefinitionsAreRefused()throws Exception {
        AbxDocument d=AbxDocument.decode(fixture(true));d.root.children("package").get(2).children("sigs").get(0).children("cert").get(0).attr("index").integer(1);
        refused(d.encode(),OWN);
    }
    @Test public void referencesDefinedOnlyAfterSharedUidAreRefused()throws Exception {
        AbxDocument d=AbxDocument.decode(fixture(true));Object controller=d.root.children("package").get(1);d.root.contents.remove(controller);d.root.contents.add(controller);refused(d.encode(),OWN);
    }
    @Test public void cleanupDetectsAnotherSystemUidAppWithTheSameSigner()throws Exception {
        AbxDocument d=AbxDocument.decode(fixture(true));AbxDocument.Element p=d.root.children("package").get(3);p.attr("userId").integer(1000);p.children("sigs").get(0).children("cert").get(0).attr("index").integer(1);
        assertTrue(new PackageTrust(d.encode()).usedByAnotherSystemPackage(OWN));
        p.attr("name").value=D2Protocol.HELPER;assertFalse(new PackageTrust(d.encode()).usedByAnotherSystemPackage(OWN));
    }
}
