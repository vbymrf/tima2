// Список устройств аккаунта и отзыв (key-lifecycle.md §5). Появилось вместе с
// привязкой по QR: если подключить устройство стало делом одного скана, то и
// отключить его человек должен уметь сам, не через поддержку и не через базу.
package api

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"log"
	"net/http"
	"strings"
	"time"

	"tima/server/internal/auth"
	"tima/server/internal/store"
)

// listMyDevices — GET /api/v1/devices: свои активные устройства.
// Только свои: чужой список устройств — это карта того, чем человек пользуется.
func listMyDevices(deps devicesDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		id, _ := auth.FromContext(r.Context())
		devices, err := deps.store.ListUserDevices(r.Context(), id.UserID)
		if err != nil {
			log.Printf("listMyDevices: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		type item struct {
			DeviceID  string `json:"device_id"`
			Name      string `json:"name"`
			CreatedAt string `json:"created_at"`
			Current   bool   `json:"current"`
		}
		out := make([]item, 0, len(devices))
		for _, d := range devices {
			out = append(out, item{
				DeviceID:  d.DeviceID,
				Name:      d.Name,
				CreatedAt: d.CreatedAt.UTC().Format(time.RFC3339),
				Current:   d.DeviceID == id.DeviceID,
			})
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"devices": out})
	}
}

// normalizePlatform приводит самообъявление клиента к допустимому значению
// колонки; всё незнакомое становится пустой строкой (устройство без платформы
// просто не сможет подтверждать привязку — это безопасная сторона отказа).
func normalizePlatform(v string) string {
	switch strings.ToLower(strings.TrimSpace(v)) {
	case "android":
		return "android"
	case "ios":
		return "ios"
	case "desktop":
		return "desktop"
	default:
		return ""
	}
}

// setMyPlatform — PUT /api/v1/devices/me/platform: устройство объявляет свою
// платформу. Клиент вызывает это при запуске, чтобы установки, созданные до
// миграции 0029, получили платформу и не потеряли возможность подтверждать QR.
func setMyPlatform(deps devicesDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		id, _ := auth.FromContext(r.Context())
		var req struct {
			Platform string `json:"platform"`
		}
		if err := json.NewDecoder(io.LimitReader(r.Body, 1024)).Decode(&req); err != nil {
			writeErr(w, http.StatusBadRequest, "bad_json", "тело не парсится")
			return
		}
		platform := normalizePlatform(req.Platform)
		if platform == "" {
			writeErr(w, http.StatusBadRequest, "bad_platform", "platform: android, ios или desktop")
			return
		}
		if err := deps.store.SetDevicePlatform(r.Context(), id.DeviceID, platform); err != nil {
			log.Printf("setMyPlatform: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]string{"platform": platform})
	}
}

// revokeDevice — DELETE /api/v1/devices/{deviceID}: отозвать своё устройство.
//
// Отозвать последнее активное устройство нельзя: аккаунт остался бы без единой
// точки входа — ни писать, ни восстановить историю, ни отозвать что-либо ещё.
// Для «уйти совсем» есть удаление аккаунта, и оно говорит о последствиях прямо.
func revokeDevice(deps devicesDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		id, _ := auth.FromContext(r.Context())
		deviceID := r.PathValue("deviceID")
		if deviceID == "" {
			writeErr(w, http.StatusBadRequest, "bad_request", "нужен device_id")
			return
		}
		ctx := r.Context()
		owned, err := deps.store.IsActiveDevice(ctx, id.UserID, deviceID)
		if err != nil {
			log.Printf("revokeDevice: ownership: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		if !owned {
			writeErr(w, http.StatusNotFound, "device_not_found", "устройство не найдено или уже отозвано")
			return
		}
		active, err := deps.store.CountActiveDevices(ctx, id.UserID)
		if err != nil {
			log.Printf("revokeDevice: count: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		if active <= 1 {
			writeErr(w, http.StatusConflict, "last_device",
				"Это единственное устройство аккаунта — отозвать его нельзя. Чтобы уйти совсем, удалите аккаунт.")
			return
		}
		if err := deps.store.RevokeDevice(ctx, id.UserID, deviceID); errors.Is(err, store.ErrDeviceNotFound) {
			writeErr(w, http.StatusNotFound, "device_not_found", "устройство не найдено или уже отозвано")
			return
		} else if err != nil {
			log.Printf("revokeDevice: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		log.Printf("revokeDevice: %s отозвал %s", id.DeviceID, deviceID)
		requestRotationAfterRevoke(deps, ctx, id.UserID, deviceID)
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"revoked": true, "device_id": deviceID})
	}
}

// requestGroupRotationAfterRevoke — просит админов групп сменить GK после отзыва
// устройства (key-lifecycle.md §5, пункт 3).
//
// Почему «просит», а не делает: групповой ключ генерирует клиент, сервер его не
// видит никогда (crypto-protocol §4) — самостоятельно ротировать ему нечем. Он
// может только сказать тем, у кого ключ есть, что пора.
//
// Зачем вообще: отозванное устройство теряет доступ к API и новых версий GK не
// получит — но ТЕКУЩУЮ версию оно уже держит. Пока она текущая, всё, что зашифровано
// под ней, для него читаемо, если ciphertext попадёт к нему другим путём.
// Ротация делает следующие сообщения непрочитаемыми: новый GK заворачивается
// только на действующие устройства (`devicesOf` отдаёт неотозванные), и
// отозванного среди них уже нет.
//
// Ошибки только логируем: отзыв уже состоялся и должен остаться успешным —
// несостоявшееся уведомление означает отложенную ротацию, а не отменённый отзыв.
func requestRotationAfterRevoke(deps devicesDeps, ctx context.Context, userID, revokedDeviceID string) {
	groups, err := deps.store.ListGroupsForUser(ctx, userID)
	if err != nil {
		log.Printf("requestGroupRotationAfterRevoke: groups: %v", err)
		return
	}
	for _, g := range groups {
		members, err := deps.store.ListGroupMembers(ctx, g.GroupID)
		if err != nil {
			log.Printf("requestGroupRotationAfterRevoke: members %s: %v", g.GroupID, err)
			continue
		}
		for _, m := range members {
			if m.Role != "owner" && m.Role != "admin" {
				continue // ротацию принимает только админ (groupRotate проверяет роль)
			}
			devices, err := deps.store.ListDevices(ctx, m.UserID)
			if err != nil {
				continue
			}
			for _, d := range devices {
				if d.DeviceID == revokedDeviceID {
					continue
				}
				deps.notifier.Device(ctx, d.DeviceID, "group.rotation_needed", map[string]any{
					"group_id": g.GroupID,
					"reason":   "device_revoked",
				})
			}
		}
	}
}
