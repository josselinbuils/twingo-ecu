#include "waveshare_twai_port.h" // Include the TWAI port header

#define TAG "TWAI"

static bool driver_installed = false; // Flag to check if the driver is installed
unsigned long previousMillis = 0; // Will store last time a message was sent

// TWAI configuration settings
static const twai_timing_config_t t_config =
  TWAI_TIMING_CONFIG_500KBITS(); // Timing configuration for 500 kbps
static const twai_filter_config_t f_config =
  TWAI_FILTER_CONFIG_ACCEPT_ALL(); // Filter configuration to accept all messages
static const twai_general_config_t g_config = TWAI_GENERAL_CONFIG_DEFAULT(
  TX_GPIO_NUM, RX_GPIO_NUM, TWAI_MODE_LISTEN_ONLY
); // General configuration in listen-only mode

static void handle_rx_message(twai_message_t message) { // Handle received TWAI message
  // Process received message
  if (message.extd) {
    ESP_LOGI(TAG, "Message is in Extended Format"); // Log message format
  } else {
    ESP_LOGI(TAG, "Message is in Standard Format"); // Log message format
  }
  printf("ID: %lx\nByte:", message.identifier); // Print message identifier
  if (!(message.rtr)) { // Check if it's not a Remote Transmission Request (RTR)
    for (int i = 0; i < message.data_length_code; i++) { // Loop through message data
      printf(" %d = %02x,", i, message.data[i]); // Print each byte in hex format
    }
    printf("\r\n"); // New line after printing data
  }
}

esp_err_t waveshare_twai_init() // Initialize TWAI driver
{
  printf("pins %d %d", TX_GPIO_NUM, RX_GPIO_NUM);

  // Install TWAI driver
  if (twai_driver_install(&g_config, &t_config, &f_config) == ESP_OK) {
    ESP_LOGI(TAG, "Driver installed"); // Log driver installation success
  } else {
    ESP_LOGI(TAG, "Failed to install driver"); // Log driver installation failure
    return ESP_FAIL;
  }

  // Start TWAI driver
  if (twai_start() == ESP_OK) {
    ESP_LOGI(TAG, "Driver started"); // Log driver start success
  } else {
    ESP_LOGI(TAG, "Failed to start driver"); // Log driver start failure
    return ESP_FAIL;
  }

  // Reconfigure alerts to detect frame receive, Bus-Off error and RX queue full states
  uint32_t alerts_to_enable = TWAI_ALERT_RX_DATA | TWAI_ALERT_ERR_PASS | TWAI_ALERT_BUS_ERROR |
    TWAI_ALERT_RX_QUEUE_FULL; // Configure alerts
  if (twai_reconfigure_alerts(alerts_to_enable, NULL) == ESP_OK) {
    ESP_LOGI(TAG, "CAN Alerts reconfigured"); // Log alert reconfiguration success
  } else {
    ESP_LOGI(TAG, "Failed to reconfigure alerts"); // Log alert reconfiguration failure
    return ESP_FAIL;
  }

  // TWAI driver is now successfully installed and started
  driver_installed = true; // Set driver installed flag
  return ESP_OK; // Return success
}

esp_err_t waveshare_twai_receive() // Receive messages via TWAI
{
  if (!driver_installed) { // Check if the driver is installed
    // Driver not installed
    vTaskDelay(pdMS_TO_TICKS(1000)); // Wait before retrying
    return ESP_FAIL; // Return failure
  }

  // Check if alert happened
  uint32_t alerts_triggered; // Variable to hold triggered alerts
  twai_read_alerts(&alerts_triggered, pdMS_TO_TICKS(POLLING_RATE_MS)); // Read triggered alerts
  twai_status_info_t twaistatus; // Variable to hold TWAI status information
  twai_get_status_info(&twaistatus); // Get TWAI status information

  // Handle alerts
  if (alerts_triggered & TWAI_ALERT_ERR_PASS) { // Check for error passive alert
    ESP_LOGI(TAG, "Alert: TWAI controller has become error passive."); // Log error passive alert
  }
  if (alerts_triggered & TWAI_ALERT_BUS_ERROR) { // Check for bus error alert
    ESP_LOGI(
      TAG, "Alert: A (Bit, Stuff, CRC, Form, ACK) error has occurred on the bus."
    ); // Log bus error alert
    ESP_LOGI(TAG, "Bus error count: %" PRIu32, twaistatus.bus_error_count); // Log bus error count
  }

  if (alerts_triggered & TWAI_ALERT_RX_QUEUE_FULL) { // Check for RX queue full alert
    ESP_LOGI(
      TAG, "Alert: The RX queue is full causing a received frame to be lost."
    ); // Log RX queue full alert
    ESP_LOGI(TAG, "RX buffered: %" PRIu32, twaistatus.msgs_to_rx); // Log buffered RX messages
    ESP_LOGI(TAG, "RX missed: %" PRIu32, twaistatus.rx_missed_count); // Log missed RX messages
    ESP_LOGI(TAG, "RX overrun %" PRIu32, twaistatus.rx_overrun_count); // Log RX overrun count
  }

  // Check if message is received
  if (alerts_triggered & TWAI_ALERT_RX_DATA) { // If RX data alert is triggered
    // One or more messages received. Handle all.
    twai_message_t message; // Variable to hold received message
    while (twai_receive(&message, 0) == ESP_OK) { // Receive messages until none are left
      handle_rx_message(message); // Process each received message
    }
  } else {
    ESP_LOGI(TAG, "No message received");
  }
  return ESP_OK; // Return success
}
