package com.example.yaesucatcontroladv;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.function.Predicate;

public class CwTextRepairTest {

    private static final Predicate<String> NICIUN_CUNOSCUT = call -> false;

    private static String repara(String text) {
        return CwTextRepair.repara(text, NICIUN_CUNOSCUT);
    }

    @Test
    public void lipesteIndicativRuptSiCorecteazaRepetareaStricata() {
        assertEquals("CQ CQ CQ DE F6BII F6BII F6BII F6BII EC K",
                repara("CQ CQ CQDEF6 BII F6 BI I F6 BII PF6 B I I EC K"));
    }

    @Test
    public void separaInContinuareCuvinteleLipite() {
        assertEquals("CQ CQ DE TM4GGR TM4GGR", repara("CQCQDETM4GGRTM4GGR"));
        assertEquals("OV1CDX OV1CDX", repara("OV1CDXOV1CDX"));
    }

    @Test
    public void nuLipesteCuvinteCwUzuale() {
        assertEquals("UR 5NN TU", repara("UR 5NN TU"));
        assertEquals("R 5NN", repara("R 5NN"));
        assertEquals("R R TU 5NN 001 BK", repara("R R TU 5NN 001 BK"));
        assertEquals("RST 599 599", repara("RST 599 599"));
        assertEquals("NAME IS ED ED", repara("NAME IS ED ED"));
        assertEquals("TNX FER CALL 73", repara("TNX FER CALL 73"));
    }

    @Test
    public void lipesteIndicativRuptDupaPrefix() {
        assertEquals("DE W1AW W1AW K", repara("DE W 1AW W1AW K"));
        assertEquals("CQ TEST DE OK1RE OK1RE OK1RE TEST", repara("CQ TEST DE OK1 RE OK1RE OK 1RE TEST"));
    }

    @Test
    public void nuCorecteazaIndicativeleDiferiteSauConfirmate() {
        // Doua statii diferite, fiecare o singura data: nu exista o majoritate.
        assertEquals("DE F6BII F6BIJ", repara("DE F6BII F6BIJ"));
        // PF6BII confirmat in MASTER.SCP ramane neatins.
        assertEquals("F6BII F6BII PF6BII",
                CwTextRepair.repara("F6BII F6BII PF6BII", call -> call.equals("PF6BII")));
    }

    @Test
    public void textGolRamaneNeschimbat() {
        assertEquals("", repara(""));
        assertEquals(null, repara(null));
    }
}
