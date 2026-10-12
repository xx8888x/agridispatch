package com.agridispatch.service;

import com.agridispatch.common.ApiException;
import com.agridispatch.entity.Farmer;
import com.agridispatch.entity.User;
import com.agridispatch.repository.FarmerRepository;
import com.agridispatch.repository.UserRepository;
import com.agridispatch.security.AuthContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 農場主的農場登錄（預約派工第一步）：一個農場主帳號對應一筆 farmers（id = token.ref）。
 * 自助註冊時已建立；種子帳號（F001、F002）只有 users，第一次預約前在此補登。
 * 聯絡人姓名存在 users.name（與頁首、訂單的農場主姓名一致）。
 */
@Service
public class FarmProfileService {

    private final FarmerRepository farmerRepository;
    private final UserRepository userRepository;

    public FarmProfileService(FarmerRepository farmerRepository, UserRepository userRepository) {
        this.farmerRepository = farmerRepository;
        this.userRepository = userRepository;
    }

    /** 尚未登錄農場回 null */
    public Map<String, Object> get(AuthContext ctx) {
        String farmerId = requireFarmerId(ctx);
        Optional<Farmer> f = farmerRepository.findById(farmerId);
        return f.map(farmer -> toView(farmer, contactName(ctx))).orElse(null);
    }

    /** 新增或修改：farmName、contactName、phone、address 必填，farmType 選填 */
    @Transactional
    public Map<String, Object> save(AuthContext ctx, Map<String, Object> req) {
        String farmerId = requireFarmerId(ctx);
        String farmName = trimmed(req.get("farmName"));
        String contactName = trimmed(req.get("contactName"));
        String phone = trimmed(req.get("phone"));
        String address = trimmed(req.get("address"));
        String farmType = trimmed(req.get("farmType"));

        if (farmName == null || farmName.length() > 100) throw ApiException.badRequest("請填寫農場名稱（100 字以內）");
        if (contactName == null || contactName.length() > 50) throw ApiException.badRequest("請填寫聯絡人姓名（50 字以內）");
        String tel = phone == null ? "" : phone.replaceAll("[\\s-]", "");
        if (!tel.matches("0\\d{8,9}")) throw ApiException.badRequest("請填寫正確的聯絡電話（手機 09 開頭 10 碼，市話含區碼）");
        if (address == null || address.length() > 255) throw ApiException.badRequest("請填寫農場地址或地號（255 字以內）");
        if (farmType != null && farmType.length() > 50) throw ApiException.badRequest("農場類型請在 50 字以內");

        // 自助註冊以 farmers.phone 判斷手機是否已註冊（findByPhone 只能有一筆），不可與其他農場主重複
        boolean taken = farmerRepository.findAll().stream()
                .anyMatch(o -> !o.getId().equals(farmerId) && tel.equals(o.getPhone()));
        if (taken) throw ApiException.conflict("此電話已被其他農場主使用");

        Farmer farmer = farmerRepository.findById(farmerId).orElseGet(() -> {
            Farmer n = new Farmer();
            n.setId(farmerId);
            return n;
        });
        farmer.setName(farmName);
        farmer.setPhone(tel);
        farmer.setAddress(address);
        if (farmType != null) farmer.setFarmType(farmType);
        farmerRepository.save(farmer);

        userRepository.findById(ctx.sub()).ifPresent(u -> {
            if (!contactName.equals(u.getName())) {
                u.setName(contactName);
                userRepository.save(u);
            }
        });
        return toView(farmer, contactName);
    }

    private String requireFarmerId(AuthContext ctx) {
        if (!ctx.isFarmer() || ctx.ref() == null || ctx.ref().isBlank()) throw ApiException.forbidden();
        return ctx.ref();
    }

    private String contactName(AuthContext ctx) {
        return userRepository.findById(ctx.sub()).map(User::getName).orElse(null);
    }

    private static Map<String, Object> toView(Farmer f, String contactName) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("farmerId", f.getId());
        m.put("farmName", f.getName());
        m.put("contactName", contactName);
        m.put("phone", f.getPhone());
        m.put("address", f.getAddress());
        m.put("farmType", f.getFarmType());
        return m;
    }

    private static String trimmed(Object o) {
        if (o == null) return null;
        String s = o.toString().trim();
        return s.isEmpty() ? null : s;
    }
}
