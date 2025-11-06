/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

#include "ble.h"
#include "esp_log.h"
#include "nvs_flash.h"
#include "console/console.h"
#include "host/ble_hs.h"
#include "host/util/util.h"
#include "nimble/nimble_port.h"
#include "nimble/nimble_port_freertos.h"
#include "services/gap/ble_svc_gap.h"

#define TAG "BLE"

static const ble_uuid_t *remote_svc_uuid = BLE_UUID128_DECLARE(
  0x29, 0x3a, 0x58, 0x56, 0xef, 0xdf, 0x10, 0xbc, 0xfa, 0x4f, 0x77, 0x84, 0x50, 0x46, 0x39, 0x39,
); // 39394651-8477-4ffa-bc10-dfef56583a29

static const ble_uuid_t *remote_chr_current_music_uuid = BLE_UUID128_DECLARE(
  0x29, 0x3a, 0x58, 0x56, 0xef, 0xdf, 0x10, 0xbc, 0xfa, 0x4f, 0x77, 0x84, 0x51, 0x46, 0x39, 0x39,
);

static const ble_uuid_t *remote_chr_music_cover_uuid = BLE_UUID128_DECLARE(
  0x29, 0x3a, 0x58, 0x56, 0xef, 0xdf, 0x10, 0xbc, 0xfa, 0x4f, 0x77, 0x84, 0x53, 0x46, 0x39, 0x39,
);

uint8_t music_cover_map[4096];

static int blecent_gap_event(struct ble_gap_event *event, void *arg);

void ble_store_config_init(void);

void (*set_current_music_callback)(const char *current_music);
void (*set_music_cover_callback)(const uint8_t *music_cover_map);

/**
 * Called when service discovery of the specified peer has completed.
 */
static void blecent_on_disc_complete(const struct peer *peer, int status, void *arg) {
  if (status != 0) {
    // Service discovery failed  Terminate the connection
    ESP_LOGE(TAG, "Service discovery failed; status=%d conn_handle=%d", status, peer->conn_handle);
    ble_gap_terminate(peer->conn_handle, BLE_ERR_REM_USER_CONN_TERM);
    return;
  }

  /* Service discovery has completed successfully.  Now we have a complete
   * list of services, characteristics, and descriptors that the peer
   * supports.
   */
  ESP_LOGI(TAG, "Service discovery complete; status=%d conn_handle=%d", status, peer->conn_handle);

  int rc = ble_gattc_exchange_mtu(peer->conn_handle, NULL, NULL);

  if (rc != 0) {
    ESP_LOGE(TAG, "Failed to negotiate MTU; rc = %d", rc);
  }
}

/**
 * Initiates the GAP general discovery procedure.
 */
static void blecent_scan(void) {
  uint8_t own_addr_type;
  struct ble_gap_disc_params disc_params = {0};
  int rc;

  // Figure out address to use while advertising (no privacy for now)
  rc = ble_hs_id_infer_auto(0, &own_addr_type);
  if (rc != 0) {
    ESP_LOGE(TAG, "Error determining address type; rc=%d", rc);
    return;
  }

  /* Tell the controller to filter duplicates; we don't want to process
   * repeated advertisements from the same device.
   */
  disc_params.filter_duplicates = 1;

  /**
   * Perform a passive scan.  I.e., don't send follow-up scan requests to
   * each advertiser.
   */
  disc_params.passive = 1;

  // Use defaults for the rest of the parameters
  disc_params.itvl = 0;
  disc_params.window = 0;
  disc_params.filter_policy = 0;
  disc_params.limited = 0;

  rc = ble_gap_disc(own_addr_type, BLE_HS_FOREVER, &disc_params, blecent_gap_event, NULL);
  if (rc != 0) {
    ESP_LOGE(TAG, "Error initiating GAP discovery procedure; rc=%d", rc);
  }
}

/**
 * Indicates whether we should try to connect to the sender of the specified
 * advertisement.  The function returns a positive result if the device
 * advertises connectability and support for the Alert Notification service.
 */
static int blecent_should_connect(const struct ble_gap_disc_desc *disc) {
  struct ble_hs_adv_fields fields;
  int rc;
  int i;
  uint32_t peer_addr[6];

  memset(peer_addr, 0x0, sizeof peer_addr);

  // The device has to be advertising connectability
  if (disc->event_type != BLE_HCI_ADV_RPT_EVTYPE_ADV_IND &&
      disc->event_type != BLE_HCI_ADV_RPT_EVTYPE_DIR_IND) {
    return 0;
  }

  rc = ble_hs_adv_parse_fields(&fields, disc->data, disc->length_data);
  if (rc != 0) {
    return 0;
  }

  for (i = 0; i < fields.num_uuids128; i++) {
    if (ble_uuid_cmp(&fields.uuids128[i].u, remote_svc_uuid) == 0) {
      return 1;
    }
  }
  return 0;
}

/**
 * Connects to the sender of the specified advertisement of it looks
 * interesting.  A device is "interesting" if it advertises connectability and
 * support for the Alert Notification service.
 */
static void blecent_connect_if_interesting(void *disc) {
  uint8_t own_addr_type;
  int rc;
  ble_addr_t *addr;

  // Don't do anything if we don't care about this advertiser
  if (!blecent_should_connect((struct ble_gap_disc_desc *)disc)) {
    return;
  }

  ESP_LOGD(TAG, "Connect to device...");

#if !(MYNEWT_VAL(BLE_HOST_ALLOW_CONNECT_WITH_SCAN))
  // Scanning must be stopped before a connection can be initiated
  rc = ble_gap_disc_cancel();
  if (rc != 0) {
    ESP_LOGD(TAG, "Failed to cancel scan; rc=%d", rc);
    return;
  }
#endif

  // Figure out address to use for connect (no privacy for now)
  rc = ble_hs_id_infer_auto(0, &own_addr_type);
  if (rc != 0) {
    ESP_LOGE(TAG, "Error determining address type; rc=%d", rc);
    return;
  }

  /* Try to connect the the advertiser.  Allow 30 seconds (30000 ms) for
   * timeout.
   */
  addr = &((struct ble_gap_disc_desc *)disc)->addr;

  rc = ble_gap_connect(own_addr_type, addr, 30000, NULL, blecent_gap_event, NULL);

  if (rc != 0) {
    ESP_LOGE(
      TAG,
      "Failed to connect to device; addr_type=%d addr=%s; rc=%d",
      addr->type,
      addr_str(addr->val),
      rc
    );
    return;
  }
}

/**
 * The nimble host executes this callback when a GAP event occurs.  The
 * application associates a GAP event callback with each connection that is
 * established.  blecent uses the same callback for all connections.
 *
 * @param event                 The event being signalled.
 * @param arg                   Application-specified argument; unused by
 *                                  blecent.
 *
 * @return                      0 if the application successfully handled the
 *                                  event; nonzero on failure.  The semantics
 *                                  of the return code is specific to the
 *                                  particular GAP event being signalled.
 */
static int blecent_gap_event(struct ble_gap_event *event, void *arg) {
  struct ble_gap_conn_desc desc;
  struct ble_hs_adv_fields fields;
  int rc;

  switch (event->type) {
    case BLE_GAP_EVENT_DISC:
      rc = ble_hs_adv_parse_fields(&fields, event->disc.data, event->disc.length_data);
      if (rc != 0) {
        return 0;
      }

      // An advertisement report was received during GAP discovery
      print_adv_fields(&fields);

      // Try to connect to the advertiser if it looks interesting
      blecent_connect_if_interesting(&event->disc);
      return 0;

    case BLE_GAP_EVENT_CONNECT:
      // A new connection was established or a connection attempt failed
      if (event->connect.status == 0) {
        ESP_LOGI(TAG, "Connection established");

        rc = ble_gap_conn_find(event->connect.conn_handle, &desc);
        assert(rc == 0);
        print_conn_desc(&desc);

        struct peer *peer = peer_find(event->connect.conn_handle);

        if (peer == NULL) {
          // Remember peer
          rc = peer_add(event->connect.conn_handle);

          if (rc != 0) {
            ESP_LOGE(TAG, "Failed to add peer; rc=%d", rc);
            return 0;
          }
        }

        // Perform service discovery
        rc = peer_disc_all(event->connect.conn_handle, blecent_on_disc_complete, NULL);
        if (rc != 0) {
          ESP_LOGE(TAG, "Failed to discover services; rc=%d", rc);
          return 0;
        }
      } else {
        // Connection attempt failed; resume scanning
        ESP_LOGE(TAG, "Connection failed; status=%d", event->connect.status);
        blecent_scan();
      }

      return 0;

    case BLE_GAP_EVENT_DISCONNECT:
      // Connection terminated
      ESP_LOGI(TAG, "Disconnect; reason=%d ", event->disconnect.reason);
      print_conn_desc(&event->disconnect.conn);

      // Forget about peer
      peer_delete(event->disconnect.conn.conn_handle);

      // Resume scanning
      blecent_scan();
      return 0;

    case BLE_GAP_EVENT_DISC_COMPLETE:
      ESP_LOGI(TAG, "Discovery complete; reason=%d", event->disc_complete.reason);
      return 0;

    case BLE_GAP_EVENT_NOTIFY_RX:
      int buffer_len = OS_MBUF_PKTLEN(event->notify_rx.om);

      // Peer sent us a notification or indication
      ESP_LOGI(
        TAG,
        "Received %s; conn_handle=%d attr_handle=%d attr_len=%d",
        event->notify_rx.indication ? "indication" : "notification",
        event->notify_rx.conn_handle,
        event->notify_rx.attr_handle,
        buffer_len
      );

      if (event->notify_rx.indication) {
        if (event->notify_rx.attr_handle == 3) {
          ESP_LOGI(TAG, "Peer disconnected");
          ble_gap_terminate(event->notify_rx.conn_handle, BLE_ERR_REM_USER_CONN_TERM);
          (*set_current_music_callback)("");
        }
        return 0;
      }

      struct peer *peer = peer_find(event->notify_rx.conn_handle);

      if (peer == NULL) {
        ESP_LOGE(TAG, "Peer not found, aborting...");
        ble_gap_terminate(event->notify_rx.conn_handle, BLE_ERR_REM_USER_CONN_TERM);
        return 0;
      }

      struct peer_chr *chr = peer_chr_find(peer->cur_svc, event->notify_rx.attr_handle, NULL);

      if (chr == NULL) {
        ESP_LOGI(TAG, "Characteristic not found");
        return 0;
      }

      // Debug
      // char buf[BLE_UUID_STR_LEN];
      // ESP_LOGI(TAG, "UUID: %s", ble_uuid_to_str(&chr->chr.uuid.u, buf));

      if (ble_uuid_cmp(&chr->chr.uuid.u, remote_chr_current_music_uuid) == 0) {
        char *str;
        str = malloc(buffer_len + 1);
        os_mbuf_copydata(event->notify_rx.om, 0, buffer_len, str);
        str[buffer_len] = '\0';
        ESP_LOGI(TAG, "Current music received: %s", str);
        (*set_current_music_callback)(str);
        free(str);
      } else if (ble_uuid_cmp(&chr->chr.uuid.u, remote_chr_music_cover_uuid) == 0) {
        uint16_t offset;

        os_mbuf_copydata(event->notify_rx.om, 0, 2, &offset);
        os_mbuf_copydata(event->notify_rx.om, 2, buffer_len - 2, music_cover_map + offset);

        for (int i = offset; i < buffer_len - 2; i++) {
          music_cover_map[i] &= 0xff;
        }

        ESP_LOGI(TAG, "Music cover part received: offset=%u", offset);

        if (offset == 0) {
          (*set_music_cover_callback)(music_cover_map);
        }
      }
      return 0;

    case BLE_GAP_EVENT_MTU:
      ESP_LOGI(
        TAG,
        "MTU update event; conn_handle=%d cid=%d mtu=%d",
        event->mtu.conn_handle,
        event->mtu.channel_id,
        event->mtu.value
      );
      return 0;

    case BLE_GAP_EVENT_REPEAT_PAIRING:
      /* We already have a bond with the peer, but it is attempting to
       * establish a new secure link.  This app sacrifices security for
       * convenience: just throw away the old bond and accept the new link.
       */

      // Delete the old bond
      rc = ble_gap_conn_find(event->repeat_pairing.conn_handle, &desc);
      assert(rc == 0);
      ble_store_util_delete_peer(&desc.peer_id_addr);

      /* Return BLE_GAP_REPEAT_PAIRING_RETRY to indicate that the host should
       * continue with the pairing operation.
       */
      return BLE_GAP_REPEAT_PAIRING_RETRY;

    default:
      ESP_LOGI(TAG, "Unknown event received: %d", event->type);
      return 0;
  }
}

static void blecent_on_reset(int reason) {
  ESP_LOGE(TAG, "Resetting state; reason=%d", reason);
}

static void blecent_on_sync(void) {
  int rc;

  // Make sure we have proper identity address set (public preferred)
  rc = ble_hs_util_ensure_addr(0);
  assert(rc == 0);

  // Begin scanning for a peripheral to connect to
  blecent_scan();
}

void blecent_host_task(void *param) {
  ESP_LOGI(TAG, "BLE Host Task Started");
  // This function will return only when nimble_port_stop() is executed
  nimble_port_run();

  nimble_port_freertos_deinit();
}

void init_ble(void (*current_music_callback)(), void (*music_cover_callback)()) {
  // Initialize NVS — it is used to store PHY calibration data
  esp_err_t ret = nvs_flash_init();
  if (ret == ESP_ERR_NVS_NO_FREE_PAGES || ret == ESP_ERR_NVS_NEW_VERSION_FOUND) {
    ESP_ERROR_CHECK(nvs_flash_erase());
    ret = nvs_flash_init();
  }
  ESP_ERROR_CHECK(ret);

  set_current_music_callback = current_music_callback;
  set_music_cover_callback = music_cover_callback;

  ret = nimble_port_init();
  if (ret != ESP_OK) {
    ESP_LOGE(TAG, "Failed to init nimble %d ", ret);
    return;
  }

  // Configure the host
  ble_hs_cfg.reset_cb = blecent_on_reset;
  ble_hs_cfg.sync_cb = blecent_on_sync;
  ble_hs_cfg.store_status_cb = ble_store_util_status_rr;

  int rc;
  // Initialize data structures to track connected peers
  rc = peer_init(MYNEWT_VAL(BLE_MAX_CONNECTIONS), 64, 64, 64);
  assert(rc == 0);

  // XXX Need to have template for store
  ble_store_config_init();

  nimble_port_freertos_init(blecent_host_task);
}
