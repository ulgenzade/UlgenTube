// Kök düzeyindeki Gradle yapılandırma dosyası.
// Alt modüllerde kullanılan eklentiler burada apply false ile tanımlanır.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}
