package com.agridispatch.service;

import com.agridispatch.common.ApiException;
import com.agridispatch.common.IdGenerator;
import com.agridispatch.entity.Farm;
import com.agridispatch.entity.Farmer;
import com.agridispatch.repository.FarmRepository;
import com.agridispatch.repository.FarmerRepository;
import com.agridispatch.repository.UserRepository;
import com.agridispatch.security.AuthContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 農場主的農場（預約派工第一步）：一個農場主（token.ref）可登錄多個農場，預約時選一個。
 * 自助註冊時填的農場（farmers 表）在第一次讀清單時自動轉成第一個農場；種子帳號（F001、F002）沒有，第一次預約前登錄。
 */
@Service
public class FarmProfileService {

    public static final int MAX_FARMS = 20;

    private final FarmRepository farmRepository;
    private final FarmerRepository farmerRepository;
    private final UserRepository userRepository;

    public FarmProfileService(FarmRepository farmRepository, FarmerRepository farmerRepository,
                              UserRepository userRepository) {
        this.farmRepository = farmRepository;
        this.farmerRepository = farmerRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public List<Map<String, Object>> list(AuthContext ctx) {
        String farmerId = requireFarmerId(ctx);
        List<Farm> farms = farmRepository.findByFarmerIdOrderByCreatedAtAscIdAsc(farmerId);
        if (farms.isEmpty()) {
            Optional<Farmer> registered = farmerRepository.findById(farmerId);
            if (registered.isPresent()) {
                Farmer f = registered.get();
                Farm first = new Farm();
                first.setId(nextId());
                first.setFarmerId(farmerId);
                first.setName(f.getName());
                first.setContactName(userRepository.findById(ctx.sub()).map(u -> u.getName()).orElse(f.getName()));
                first.setPhone(f.getPhone());
                first.setAddress(f.getAddress());
                first.setFarmType(f.getFarmType());
                farms = List.of(farmRepository.save(first));
            }
        }
        return farms.stream().map(FarmProfileService::toView).toList();
    }

    @Transactional
    public Map<String, Object> create(AuthContext ctx, Map<String, Object> req) {
        String farmerId = requireFarmerId(ctx);
        if (farmRepository.countByFarmerId(farmerId) >= MAX_FARMS) {
            throw ApiException.conflict("每個帳號最多登錄 " + MAX_FARMS + " 個農場");
        }
        Farm farm = new Farm();
        farm.setId(nextId());
        farm.setFarmerId(farmerId);
        apply(farm, req);
        return toView(farmRepository.save(farm));
    }

    @Transactional
    public Map<String, Object> update(AuthContext ctx, String id, Map<String, Object> req) {
        Farm farm = requireOwn(ctx, id);
        apply(farm, req);
        return toView(farmRepository.save(farm));
    }

    /** 刪除只影響之後的預約；既有訂單已記下當時的地點與聯絡人 */
    @Transactional
    public void delete(AuthContext ctx, String id) {
        farmRepository.delete(requireOwn(ctx, id));
    }

    private Farm requireOwn(AuthContext ctx, String id) {
        String farmerId = requireFarmerId(ctx);
        Farm farm = farmRepository.findById(id).orElseThrow(() -> ApiException.notFound("找不到這個農場"));
        if (!farmerId.equals(farm.getFarmerId())) throw ApiException.notFound("找不到這個農場");   // 不透露他人農場是否存在
        return farm;
    }

    /** farmName、contactName、phone、address 必填，farmType 選填 */
    private static void apply(Farm farm, Map<String, Object> req) {
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

        farm.setName(farmName);
        farm.setContactName(contactName);
        farm.setPhone(tel);
        farm.setAddress(address);
        farm.setFarmType(farmType != null ? farmType : "個人農戶");
    }

    private String nextId() {
        List<String> ids = farmRepository.findAll().stream().map(Farm::getId).toList();
        return IdGenerator.next("FM", 4, ids, farmRepository::existsById);
    }

    private String requireFarmerId(AuthContext ctx) {
        if (!ctx.isFarmer() || ctx.ref() == null || ctx.ref().isBlank()) throw ApiException.forbidden();
        return ctx.ref();
    }

    private static Map<String, Object> toView(Farm f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", f.getId());
        m.put("farmName", f.getName());
        m.put("contactName", f.getContactName());
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
