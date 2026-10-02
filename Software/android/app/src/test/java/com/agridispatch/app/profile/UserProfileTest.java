package com.agridispatch.app.profile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 分類資料表 (docs/角色與人力分類表.md) 與啟動頁、語言的對應。
 */
public class UserProfileTest {

    @Test
    public void serverOpensAdminLogin() {
        UserProfile p = UserProfile.admin();
        assertEquals(AppSide.SERVER, p.side);
        assertEquals("科技後台/admin-login.html", p.entryPage());
        assertEquals("zh", p.language());
    }

    @Test
    public void farmerOpensFarmerLogin() {
        assertEquals("農業派遣主網站/farmer-login.html", UserProfile.farmer().entryPage());
        assertEquals("farmer", ClientRole.FARMER.platformRole);
    }

    @Test
    public void localWorkerTypesMapToFrontendTracks() {
        assertEquals("農業派遣主網站/worker-local-login.html?role=labor",
                UserProfile.localWorker(LocalWorkerType.SENIOR).entryPage());
        assertEquals("labor", LocalWorkerType.RETURNING_WOMEN.track);
        assertEquals("labor", LocalWorkerType.STUDENT.track);
        assertEquals("農業派遣主網站/worker-local-login.html?role=foreman",
                UserProfile.localWorker(LocalWorkerType.FOREMAN).entryPage());
        assertEquals("農業派遣主網站/worker-local-login.html?role=machinery",
                UserProfile.localWorker(LocalWorkerType.MACHINERY).entryPage());
        assertEquals("zh", UserProfile.localWorker(LocalWorkerType.STUDENT).language());
    }

    @Test
    public void foreignWorkerLanguageFollowsNationality() {
        assertEquals("農業派遣主網站/worker-foreign-login.html",
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
