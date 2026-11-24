# QR Based Smart Inventory App (QR Mart)

An Android supermarket app (Java + XML) where shoppers scan product QR codes into a basket, pay via Razorpay or UPI/QR, and get a PDF bill, while the store manager manages stock, prints QR labels, and views sales reports.

Mobile App Development end-semester project. Team: Ashith (backend, data, reports) and Sriniketh (UI, scanner, device features).

## Features
- **Shopper (Customer):**
  - Browse, search (text or voice), and filter products by category and sort order.
  - Scan a product QR code and pick a quantity in a bottom sheet; stock is **reserved immediately** in inventory when items are added to the basket.
  - **5-minute cart & payment timer:** items held in the basket have a live 5-minute countdown on both the Basket and Checkout screens. If unpaid when time expires (or if you cancel the order / clear / remove items), all reserved quantities are automatically returned to inventory.
  - Edit the basket (stepper, swipe to delete with undo, shake to clear) and checkout with optional 10% coupon discount + 5% GST.
  - **Multiple payment options:**
    - **Pay with Razorpay** (Razorpay Checkout SDK in test mode using `rzp_test_THeRsyfbR2l4r2`) with automatic payment verification and PDF invoice generation.
    - **Scan QR & Pay** (scans any UPI QR code with CameraX + ML Kit and launches installed UPI apps via Android `upi://pay` intents).
    - **Pay with UPI ID** (enter a payee UPI ID and launch your default UPI app).
    - **Cancel order & return items to stock**.
  - View or share the generated PDF bill and review past customer orders with one-tap re-order.
- **Admin (Store Manager):**
  - Add, edit, and delete products; auto-fill product details from a real packet barcode (Open Food Facts API).
  - Generate and print individual QR labels or a full printable QR sheet.
  - **Reports & analytics (Admin only):** stock-by-category pie chart, top-sellers bar chart, daily-sales line chart, inventory & low-stock CSV export, CSV import, and manual low-stock/expiry check.
  - **Settings & background work (Admin only):** low-stock and expiry alert preferences (WorkManager every 12 hours) and foreground CSV inventory backup to Downloads.
- **Device features:** CameraX + ML Kit scanning, light sensor auto torch, accelerometer shake-to-clear, GPS with OpenStreetMap nearest-store map, low-battery and offline banners, custom **QR Mart** adaptive & monochrome launcher icon.

## Tech
Java, XML, Material 3, Navigation Component, Room (SQLite), ViewModel and LiveData, CameraX, ML Kit, ZXing, Razorpay Checkout SDK (`1.6.41`), Retrofit and Gson, WorkManager, FileProvider, ContentProvider, MPAndroidChart, osmdroid. Gradle with Kotlin DSL and a version catalog.

## Run it
1. Clone the repository and open it in **Android Studio**.
2. Wait for Gradle sync to finish (Android Studio generates `local.properties` automatically for your local Android SDK; no extra key setup is needed for test mode).
3. Run on an emulator or physical Android phone (a physical phone is recommended for the camera, torch, shake detector, and UPI apps).
4. Open [`product_qr_codes.html`](product_qr_codes.html) in any desktop browser (or print it) to scan all 24 seeded product QR codes (`PROD-1001` to `PROD-1024`) with your phone during testing and demos.

To build and run unit tests from the command line:
```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest
```

## Demo accounts
| Role | Email | Password |
|---|---|---|
| Customer | shopper@qrmart.com | shop123 |
| Admin | admin@qrmart.com | admin123 |

New admin accounts require the code `QRMART-ADMIN`. The first launch seeds 24 products (QR codes `PROD-1001` to `PROD-1024`) and a week of sample sales.

## Project structure
`ui` screens and adapters, `viewmodel` ViewModels, `data` database, repository and ContentProvider, `model` entities, `network` Retrofit, `service` WorkManager (`StockCheckWorker`, `CartExpiryWorker`), backup service and receivers, `utils` PDF, CSV, charts, payment QR parser, and cart reservation manager.

## Notes
- **Testing Razorpay:** Tap **Pay with Razorpay** on Checkout and use Razorpay's test UPI ID `success@razorpay` (or test cards/netbanking) to verify instant order completion and PDF invoice generation.
- **USB & UPI apps:** Real UPI apps (GPay/PhonePe/Navi) on physical phones often block payments while a USB debugging cable is plugged in; unplug USB when testing external UPI apps, or use Razorpay Test Mode over Wi-Fi/mobile data.
- The emulator has no flash and cannot read QR codes from its virtual camera; use a physical phone for camera scanning.
- Login is local to the device.
