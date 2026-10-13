package dev.experimental.rmgu.bootprobe;

import dev.experimental.rmgu.bootprobe.shared.HelperProtocol;
import java.io.IOException;
import java.util.*;

/** Adds/removes only this diagnostic certificate's past-signature references.
 * The two shared-UID references follow the published RMG-Next/DFReroot technique.
 * No upstream unlicensed injector code is included.
 */
final class PackageTrust {
    static final String TARGET="android.uid.system";
    final AbxDocument doc;
    final AbxDocument.Element user,sigs,past;
    final List<String> table=new ArrayList<>();
    final int packages;
    PackageTrust(byte[] raw)throws IOException {
        doc=AbxDocument.decode(raw);
        if(!Arrays.equals(raw,doc.encode()))throw new IOException("ABX round-trip is not byte-identical; refusing mutation");
        if(!doc.root.start.name.equals("packages"))throw new IOException("Unexpected package-store root");
        packages=doc.root.children("package").size();if(packages<20)throw new IOException("Package registry appears incomplete");
        for(AbxDocument.Element e:all(doc.root))if(e.start.name.equals("cert")) {
            int index=e.attr("index").integer();if(index<0||index>100_000)throw new IOException("Invalid certificate index");
            String key=optional(e,"key");
            if(key!=null){if(!key.matches("[0-9a-fA-F]+")||key.length()%2!=0)throw new IOException("Invalid certificate bytes");while(table.size()<index)table.add(null);table.add(key.toLowerCase(Locale.ROOT));}
        }
        List<AbxDocument.Element> users=new ArrayList<>();for(AbxDocument.Element e:doc.root.children("shared-user"))if(TARGET.equals(e.text("name")))users.add(e);
        if(users.size()!=1)throw new IOException("System shared UID must be present exactly once");
        user=users.get(0);if(user.attr("userId").integer()!=1000)throw new IOException("Unexpected system UID");
        sigs=one(user.children("sigs"));
        if(sigs.children("cert").size()!=sigs.attr("count").integer()||sigs.children("cert").isEmpty())throw new IOException("Invalid current system signatures");
        if(user.children("sigs").size()!=1||sigs.children("pastSigs").size()>1)throw new IOException("Unsupported system signature layout");
        past=sigs.children("pastSigs").isEmpty()?null:one(sigs.children("pastSigs"));
        if(past!=null&&past.attr("count").integer()!=past.children("cert").size())throw new IOException("Invalid past signature count");
    }
    private static AbxDocument.Element one(List<AbxDocument.Element> list)throws IOException{if(list.size()!=1)throw new IOException("Expected exactly one signature block");return list.get(0);}
    private String key(AbxDocument.Element cert)throws IOException {
        String inline=optional(cert,"key");if(inline!=null)return inline.toLowerCase(Locale.ROOT);
        int i=cert.attr("index").integer();if(i<0||i>=table.size()||table.get(i)==null)throw new IOException("Unresolved certificate reference");return table.get(i);
    }
    int ownedCount(String cert)throws IOException {int n=0;if(past!=null)for(AbxDocument.Element e:past.children("cert"))if(key(e).equals(cert))n++;return n;}
    int controllerIndex(String cert)throws IOException {
        List<AbxDocument.Element> matches=new ArrayList<>();for(AbxDocument.Element p:doc.root.children("package"))if(HelperProtocol.CONTROLLER.equals(p.text("name")))matches.add(p);
        AbxDocument.Element controller=one(matches),signatures=one(controller.children("sigs"));
        List<AbxDocument.Element> current=signatures.children("cert");
        if(current.size()!=1||signatures.attr("count").integer()!=1||!cert.equals(key(current.get(0))))throw new IOException("Installed controller signing certificate does not match");
        int index=current.get(0).attr("index").integer();
        if(index<0||index>=table.size()||!cert.equals(table.get(index)))throw new IOException("Controller certificate cannot be safely referenced");
        if(table.indexOf(cert)!=index)index=table.indexOf(cert);
        // References must already be defined before the shared-user signature block is read.
        int defined=0;boolean found=false;
        for(AbxDocument.Element e:all(doc.root)){
            if(e==user)break;
            if(e.start.name.equals("cert")&&optional(e,"key")!=null){int i=e.attr("index").integer();defined=Math.max(defined,i)+1;}
        }
        found=index<defined;
        if(!found)throw new IOException("Controller certificate is not defined before system signatures");
        for(AbxDocument.Element currentSystem:sigs.children("cert"))if(key(currentSystem).equals(cert))throw new IOException("Refusing to alter a current system/platform certificate");
        return index;
    }
    byte[] add(String cert)throws IOException {
        int index=controllerIndex(cert);
        if(ownedCount(cert)!=0)throw new IOException("Certificate already occurs in system history; no second injection is allowed");
        List<String> before=outsideFingerprint();List<String> foreign=pastFingerprint();
        AbxDocument.Element destination=past;
        if(destination==null){destination=new AbxDocument.Element("pastSigs");destination.intAttr("count",0);sigs.contents.add(destination);}
        for(int i=0;i<2;i++){AbxDocument.Element node=new AbxDocument.Element("cert");node.intAttr("index",index);node.intAttr("flags",2);destination.contents.add(node);}
        destination.attr("count").integer(destination.children("cert").size());
        byte[] result=doc.encode();PackageTrust check=new PackageTrust(result);
        if(check.ownedCount(cert)!=2||!before.equals(check.outsideFingerprint()))throw new IOException("Package data changed outside the intended signature history");
        List<String> remaining=new ArrayList<>();for(AbxDocument.Element e:check.past.children("cert"))if(!check.key(e).equals(cert))remaining.add(check.certFingerprint(e));
        if(!foreign.equals(remaining))throw new IOException("Foreign past signatures were changed");
        return result;
    }
    byte[] remove(String cert)throws IOException {
        for(AbxDocument.Element e:sigs.children("cert"))if(key(e).equals(cert))throw new IOException("Refusing to remove a current platform certificate");
        if(ownedCount(cert)==0)return doc.encode();
        List<String> before=outsideFingerprint();List<String> kept=new ArrayList<>();
        for(AbxDocument.Element e:new ArrayList<>(past.children("cert")))if(key(e).equals(cert))past.contents.remove(e);else kept.add(certFingerprint(e));
        past.attr("count").integer(past.children("cert").size());
        // Keep an empty container, preserving its other attributes; PMS can normalize it later.
        byte[] result=doc.encode();PackageTrust check=new PackageTrust(result);
        if(check.ownedCount(cert)!=0||!before.equals(check.outsideFingerprint())||!kept.equals(check.pastFingerprint()))throw new IOException("Cleanup changed unrelated signature or package data");
        return result;
    }
    private List<String> outsideFingerprint()throws IOException {
        List<String> result=new ArrayList<>();fingerprint(doc.root,result,past);return result;
    }
    private void fingerprint(AbxDocument.Element e,List<String> out,AbxDocument.Element omitted)throws IOException {
        if(e==omitted)return;
        out.add("<"+e.start.name);
        for(AbxDocument.Token t:e.attributes)out.add(t.name+":"+t.type+":"+(t.value instanceof byte[] b?AbxDocument.hex(b):String.valueOf(t.value)));
        if(e.start.name.equals("cert"))out.add("resolved:"+key(e));
        for(Object o:e.contents){if(o instanceof AbxDocument.Element n)fingerprint(n,out,omitted);else{AbxDocument.Token t=(AbxDocument.Token)o;out.add("token:"+t.event+":"+t.type+":"+t.value);}}
        out.add("/"+e.start.name);
    }
    private List<String> pastFingerprint()throws IOException{List<String> result=new ArrayList<>();if(past!=null)for(AbxDocument.Element e:past.children("cert"))result.add(certFingerprint(e));return result;}
    private String certFingerprint(AbxDocument.Element e)throws IOException{List<String> list=new ArrayList<>();fingerprint(e,list,null);return list.toString();}
    private static String optional(AbxDocument.Element e,String name)throws IOException{for(AbxDocument.Token t:e.attributes)if(t.name.equals(name))return t.text();return null;}
    private static List<AbxDocument.Element> all(AbxDocument.Element e){List<AbxDocument.Element> result=new ArrayList<>();result.add(e);for(Object o:e.contents)if(o instanceof AbxDocument.Element n)result.addAll(all(n));return result;}
}
