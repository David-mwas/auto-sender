# AutoSender

AutoSender is an Android application designed to automate the process of sending money via M-PESA. By securely saving your frequent contacts, the app allows you to initiate transfers with a single click, completely bypassing the manual SIM Toolkit flow.

## 🚀 Key Features

*   **USSD Automation:** Utilizes Android Accessibility Services (`MpesaUssdService`) to automatically navigate the `*334#` USSD menus without manual user intervention.
*   **SIM Toolkit Bypass:** Avoids the recent SIM Toolkit update that forces you to manually re-enter the recipient's phone number to confirm.
*   **Biometric Security:** Requires Fingerprint or Face Unlock authentication before any transfer is initiated to prevent unauthorized access.
*   **Secure Storage:** Uses Android's `EncryptedSharedPreferences` (Jetpack Security) to securely encrypt your M-PESA PIN at rest.
*   **Transaction Logs:** Records a rich history of successful transfers, failed attempts, and USSD errors directly in the app.
*   **Modern UI:** Built entirely with Jetpack Compose for a responsive and smooth user interface.

## 🛠 Tech Stack

*   **Language:** Kotlin
*   **UI Framework:** Jetpack Compose, Material 3
*   **Key APIs & Libraries:**
    *   Android Accessibility Service API
    *   Biometric API
    *   Jetpack Security Crypto (`EncryptedSharedPreferences`)
    *   Gson for local storage serialization

## 📋 Prerequisites

To build and run this project, you need:

*   **Android Studio:** Latest stable version recommended for full Jetpack Compose support.
*   **Android SDK:** Minimum SDK 24 (Android 7.0), Target SDK 36.
*   A physical Android device with an active Safaricom SIM card (required for USSD functionality).

## ⚙️ Setup and Installation

1.  **Open in Android Studio:**
    *   Open Android Studio and select **File > Open**.
    *   Navigate to the `autosendapp` directory and select it.
2.  **Sync Gradle:**
    *   Wait for Android Studio to index the files and sync the Gradle project.
3.  **Build and Run:**
    *   Connect your physical Android device.
    *   Click the **Run** button (green play icon) or run `./gradlew installDebug` from the terminal.

## 📱 Usage

1.  **Enable Accessibility:** Go to your device's Settings > Accessibility and enable the **AutoSender** service.
2.  **Set your PIN:** Open the app, tap the **PIN** button in the top right, and securely save your M-PESA PIN.
3.  **Add a Contact:** Tap the floating `+` button to add a frequent recipient (Name and Phone Number).
4.  **Send Money:** Tap on a saved contact, enter the amount, and tap **Send Now**. Verify your fingerprint when prompted. The app will automatically dial `*334#` and complete the transfer!
5.  **View Logs:** Tap the list icon in the top right to view the history of your automated transactions.

## ⚠️ Important Security Guidelines

*   **PIN Storage:** While this app uses robust encryption (AES256_GCM) to store your PIN locally, anyone who can bypass your phone's lock screen (or if they know your biometric fallback PIN) could potentially initiate transfers. Use at your own risk.
*   **Battery Optimization:** Ensure battery optimization is **disabled** for this app in your phone's settings to prevent the OS from terminating the USSD automation service prematurely.
