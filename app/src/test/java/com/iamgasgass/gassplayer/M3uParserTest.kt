package com.iamgasgass.gassplayer
import com.iamgasgass.gassplayer.data.M3uParser
import org.junit.Assert.*
import org.junit.Test
class M3uParserTest{@Test fun parsesAttributesAndUrls(){val x=M3uParser.parse("""#EXTM3U
#EXTINF:-1 tvg-id="rai1.it" tvg-logo="https://logo/1.png" group-title="Italia",Rai 1
https://stream/rai1.m3u8""","s");assertEquals(1,x.size);assertEquals("Rai 1",x[0].name);assertEquals("Italia",x[0].group);assertEquals("rai1.it",x[0].epgId)}}
