import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// La clave de firma no entra al repositorio: quien tenga el .jks puede publicar
// actualizaciones que Android acepta como si fueran de la app original.
val keystoreProperties = Properties().apply {
    val archivo = rootProject.file("keystore.properties")
    if (archivo.exists()) archivo.inputStream().use { load(it) }
}
val hayClaveDeRelease = keystoreProperties.getProperty("storeFile") != null

android {
    namespace = "com.ieta.smartcar"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ieta.smartcar"
        minSdk = 24
        targetSdk = 34
        versionCode = 8
        versionName = "2.2"
    }

    signingConfigs {
        if (hayClaveDeRelease) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // R8 elimina las clases de Compose que la app no usa. La app no depende de
            // reflexion sobre codigo propio, asi que no hay nada que preservar a mano:
            // la unica reflexion apunta a metodos ocultos de BluetoothDevice, que es una
            // clase del sistema y R8 no toca.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )

            // Sin keystore.properties el release se firma con la clave de depuracion.
            // Asi cualquiera puede compilar el proyecto sin tener el .jks, en vez de
            // toparse con un APK sin firmar que no se puede instalar.
            signingConfig = if (hayClaveDeRelease) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.02")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
}
