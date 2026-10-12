package com.agridispatch.app.profile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 分類資料表 (Software/android/docs/角色與人力分類表.md) 與啟動頁、語言的對應。
 */
public class UserProfileTest {

    @Test
    public void serverOpensAdminLogin() {
        UserProfile p = UserProfile.admin();
        assertEquals(AppSide.SERVER, p.side);
        assertEquals("TechBackend/admin-login.html", p.entryPage());
        assertEquals("zh", p.language());
    }

    @Test
    public void farmerOpensFarmerLogin() {
        assertEquals("AgriDispatch/farmer-login.html", UserProfile.farmer().entryPage());
        assertEquals("farmer", ClientRole.FARMER.platformRole);
    }

    @Test
    public void localTracksMapToFrontendRoleParam() {
        assertEquals("AgriDispatch/worker-local-login.html?role=labor",
                UserProfile.localWorker(LocalTrack.LABOR).entryPage());
        assertEquals("AgriDispatch/worker-local-login.html?role=foreman",
                UserProfile.localWorker(LocalTrack.FOREMAN).entryPage());
        assertEquals("AgriDispatch/worker-local-login.html?role=machinery",
                UserProfile.localWorker(LocalTrack.MACHINERY).entryPage());
        assertEquals("zh", UserProfile.localWorker(LocalTrack.LABOR).language());
    }

    @Test
    public void legacyLocalWorkerTypesMigrateToTracks() {
        // 1.0.6 以前存的細分類型，升級後轉成軌別 (ProfileStore 讀取時使用)
        assertEquals(LocalTrack.LABOR, LocalWorkerType.SENIOR.track);
        assertEquals(LocalTrack.LABOR, LocalWorkerType.RETURNING_WOMEN.track);
        assertEquals(LocalTrack.LABOR, LocalWorkerType.STUDENT.track);
        assertEquals(LocalTrack.FOREMAN, LocalWorkerType.FOREMAN.track);
        assertEquals(LocalTrack.MACHINERY, LocalWorkerType.MACHINERY.track);
    }

    @Test
    public void foreignWorkerLanguageFollowsNationality() {
        assertEquals("AgriDispatch/worker-foreign-login.html",
                UserProfile.foreignWorker(Nationality.VN).entryPage());
        assertEquals("vi", UserProfile.foreignWorker(Nationality.VN).language());
        assertEquals("id", UserProfile.foreignWorker(Nationality.ID).language());
    }

    @Test
    public void onlyVietnamAndIndonesiaAreOffered() {
        assertTrue(Nationality.VN.offered);
        assertTrue(Nationality.ID.offered);
        assertFalse(Nationality.TH.offered);
        assertFalse(Nationality.PH.offered);
        // 未開放國別即使被選到，語言也回到中文
        assertEquals("zh", UserProfile.foreignWorker(Nationality.TH).language());
    }
}
