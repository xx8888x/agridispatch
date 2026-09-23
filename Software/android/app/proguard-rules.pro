# 智農派工 (AgriDispatch) - ProGuard / R8 規則
# 目前 release 建置設定 minifyEnabled false，本檔案暫未實際生效；
# 先補上空白檔案以避免 app/build.gradle 內 proguardFiles 參照到不存在的檔案。
# 未來若開啟 minifyEnabled true，請視實際遇到的 ClassNotFoundException / NoSuchMethodError
# 於此處補充對應的 -keep 規則 (例如 org.json、Firebase、Play Services 相關類別)。
