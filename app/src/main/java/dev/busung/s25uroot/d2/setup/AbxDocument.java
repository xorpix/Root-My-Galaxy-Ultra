package dev.busung.s25uroot.d2.setup;

import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;

/** ABX v0 codec preserving every existing value's wire type and raw numeric bits.
 * Protocol reference: AOSP frameworks/libs/modules-utils BinaryXmlSerializer/FastDataOutput.
 * No conversion of the package registry to text XML is performed.
 */
final class AbxDocument {
    static final byte[] MAGIC={65,66,88,0};
    static final int NULL=1,STRING=2,INTERNED=3,BYTES_HEX=4,BYTES_BASE64=5,INT=6,INT_HEX=7,LONG=8,LONG_HEX=9,FLOAT=10,DOUBLE=11,TRUE=12,FALSE=13;
    static final class Token {
        final int event,type;
        final String name;
        Object value;
        Token(int e,int t,String n,Object v){event=e;type=t;name=n;value=v;}
        String text()throws IOException {
            if(type==STRING||type==INTERNED)return (String)value;
            if(type==BYTES_HEX)return hex((byte[])value);
            if(type==INT||type==INT_HEX)return String.valueOf(ByteBuffer.wrap((byte[])value).getInt());
            if(type==LONG||type==LONG_HEX)return String.valueOf(ByteBuffer.wrap((byte[])value).getLong());
            throw new IOException("Unsupported attribute type for "+name);
        }
        int integer()throws IOException {try{return Integer.parseInt(text());}catch(NumberFormatException e){throw new IOException("Invalid integer: "+name,e);}}
        void integer(int value)throws IOException {
            if(type==INT||type==INT_HEX)this.value=ByteBuffer.allocate(4).putInt(value).array();
            else if(type==STRING||type==INTERNED)this.value=String.valueOf(value);
            else throw new IOException("Unsupported count type");
        }
    }
    static final class Element {
        final Token start,end;
        final List<Token> attributes=new ArrayList<>();
        final List<Object> contents=new ArrayList<>();
        Element(String name){start=new Token(2,INTERNED,name,null);end=new Token(3,INTERNED,name,null);}
        Element(Token s,Token e){start=s;end=e;}
        Token attr(String name)throws IOException {
            Token result=null;for(Token t:attributes)if(t.name.equals(name)){if(result!=null)throw new IOException("Duplicate attribute "+name);result=t;}
            if(result==null)throw new IOException("Missing attribute "+name+" on "+start.name);return result;
        }
        String text(String name)throws IOException{return attr(name).text();}
        List<Element> children(String name){List<Element> result=new ArrayList<>();for(Object o:contents)if(o instanceof Element e&&e.start.name.equals(name))result.add(e);return result;}
        void intAttr(String name,int value){attributes.add(new Token(15,INT,name,ByteBuffer.allocate(4).putInt(value).array()));}
    }
    final List<Token> prefix=new ArrayList<>(),suffix=new ArrayList<>();
    Element root;
    private AbxDocument(){ }
    static AbxDocument decode(byte[] bytes)throws IOException {
        if(bytes.length<6||bytes.length>24*1024*1024||!Arrays.equals(Arrays.copyOf(bytes,4),MAGIC))throw new IOException("Only ABX version 0 package stores are supported");
        DataInputStream in=new DataInputStream(new ByteArrayInputStream(bytes,4,bytes.length-4));
        List<String> pool=new ArrayList<>();List<Token> tokens=new ArrayList<>();
        while(in.available()>0){
            if(tokens.size()>2_000_000)throw new IOException("Too many XML tokens");
            int tag=in.readUnsignedByte(),event=tag&15,type=tag>>>4;
            String name=null;Object value=null;
            if(event==2||event==3){if(type!=INTERNED)throw new IOException("Unexpected element encoding");name=interned(in,pool);}
            else if(event==15){
                name=interned(in,pool);
                value=switch(type){
                    case NULL,TRUE,FALSE->null;
                    case STRING->in.readUTF();
                    case INTERNED->interned(in,pool);
                    case BYTES_HEX,BYTES_BASE64->exact(in,in.readUnsignedShort());
                    case INT,INT_HEX,FLOAT->exact(in,4);
                    case LONG,LONG_HEX,DOUBLE->exact(in,8);
                    default->throw new IOException("Unknown ABX value type "+type);
                };
            }else if(event==0||event==1){if(type!=NULL)throw new IOException("Unexpected document marker");}
            else if(event>=4&&event<=10){if(type!=STRING)throw new IOException("Unexpected text encoding");value=in.readUTF();}
            else throw new IOException("Unknown ABX event "+event);
            tokens.add(new Token(event,type,name,value));
        }
        if(tokens.get(0).event!=0||tokens.get(tokens.size()-1).event!=1)throw new IOException("Incomplete ABX document");
        AbxDocument doc=new AbxDocument();int[] at={0};
        while(at[0]<tokens.size()&&tokens.get(at[0]).event!=2)doc.prefix.add(tokens.get(at[0]++));
        doc.root=element(tokens,at,0);
        while(at[0]<tokens.size()){Token t=tokens.get(at[0]++);if(t.event==2||t.event==3||t.event==15)throw new IOException("Additional root or stray element");doc.suffix.add(t);}
        return doc;
    }
    private static Element element(List<Token> tokens,int[] at,int depth)throws IOException {
        if(depth>64||at[0]>=tokens.size())throw new IOException("Invalid XML depth");
        Token start=tokens.get(at[0]++);if(start.event!=2)throw new IOException("Expected element");
        List<Token> attributes=new ArrayList<>();
        while(at[0]<tokens.size()&&tokens.get(at[0]).event==15)attributes.add(tokens.get(at[0]++));
        List<Object> body=new ArrayList<>();
        while(at[0]<tokens.size()&&tokens.get(at[0]).event!=3){Token t=tokens.get(at[0]);if(t.event==2)body.add(element(tokens,at,depth+1));else{if(t.event==0||t.event==1||t.event==15)throw new IOException("Misplaced XML token");body.add(t);at[0]++;}}
        if(at[0]>=tokens.size())throw new IOException("Missing end element");
        Token end=tokens.get(at[0]++);if(!start.name.equals(end.name))throw new IOException("Mismatched end element");
        Element e=new Element(start,end);e.attributes.addAll(attributes);e.contents.addAll(body);
        Set<String> names=new HashSet<>();for(Token t:attributes)if(!names.add(t.name))throw new IOException("Duplicate attribute");
        return e;
    }
    byte[] encode()throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);out.write(MAGIC);
        Map<String,Integer> pool=new LinkedHashMap<>();for(Token t:prefix)write(out,pool,t);writeElement(out,pool,root);for(Token t:suffix)write(out,pool,t);
        return bytes.toByteArray();
    }
    private static void writeElement(DataOutputStream out,Map<String,Integer> pool,Element e)throws IOException {
        write(out,pool,e.start);for(Token t:e.attributes)write(out,pool,t);for(Object o:e.contents){if(o instanceof Element n)writeElement(out,pool,n);else write(out,pool,(Token)o);}write(out,pool,e.end);
    }
    private static void write(DataOutputStream out,Map<String,Integer> pool,Token t)throws IOException {
        out.writeByte(t.event|(t.type<<4));
        if(t.event==2||t.event==3){interned(out,pool,t.name);return;}
        if(t.event==15)interned(out,pool,t.name);
        switch(t.type){
            case NULL,TRUE,FALSE:break;
            case STRING:out.writeUTF((String)t.value);break;
            case INTERNED:interned(out,pool,(String)t.value);break;
            case BYTES_HEX,BYTES_BASE64:byte[] b=(byte[])t.value;if(b.length>65535)throw new IOException("Value too large");out.writeShort(b.length);out.write(b);break;
            case INT,INT_HEX,LONG,LONG_HEX,FLOAT,DOUBLE:out.write((byte[])t.value);break;
            default:throw new IOException("Unknown encoding");
        }
    }
    private static String interned(DataInputStream in,List<String> pool)throws IOException {
        int ref=in.readUnsignedShort();if(ref!=65535){if(ref>=pool.size())throw new IOException("Invalid string reference");return pool.get(ref);}
        String value=in.readUTF();if(pool.size()<65535)pool.add(value);return value;
    }
    private static void interned(DataOutputStream out,Map<String,Integer> pool,String value)throws IOException {
        Integer ref=pool.get(value);if(ref!=null){out.writeShort(ref);return;}
        out.writeShort(65535);out.writeUTF(value);if(pool.size()<65535)pool.put(value,pool.size());
    }
    private static byte[] exact(DataInputStream in,int count)throws IOException {byte[] b=in.readNBytes(count);if(b.length!=count)throw new EOFException();return b;}
    static String hex(byte[] bytes){StringBuilder result=new StringBuilder(bytes.length*2);for(byte b:bytes)result.append("0123456789abcdef".charAt((b>>>4)&15)).append("0123456789abcdef".charAt(b&15));return result.toString();}
    static byte[] unhex(String hex)throws IOException {if(!hex.matches("[0-9a-f]{200,20000}")||hex.length()%2!=0)throw new IOException("Invalid certificate encoding");byte[] b=new byte[hex.length()/2];for(int i=0;i<b.length;i++)b[i]=(byte)Integer.parseInt(hex.substring(i*2,i*2+2),16);return b;}
}
