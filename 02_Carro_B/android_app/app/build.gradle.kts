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
        versionCode = 30
        versionName = "3.8"
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
        debug {
            // Se firma con la misma clave que el release cuando esta disponible.
            //
            // Con claves distintas, instalar un debug sobre un release obliga a
            // desinstalar primero, y con ello se pierden los ajustes guardados de
            // calibracion. Igualarlas hace que cualquier build reemplace a cualquier
            // otro sin ceremonia. Si no hay keystore, cae a la clave de depuracion y
            // el proyecto sigue compilando para quien lo clone sin el .jks.
            if (hayClaveDeRelease) {
                signingConfig = signingConfigs.getByName("release")
            }
        }

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
    // Retrocompatibilidad de la pantalla de arranque del sistema. Sin esto, en Android
    // 11 y anteriores la app abre con un destello del fondo de la ventana.
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
}
