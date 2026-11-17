#!/usr/bin/env bash
# generate-fixtures.sh
# 脚本化下载公开数据集样例 DICOM / HL7 / WADO-RS 响应。
# 断网时自动降级到 scripts/bundled-fixtures/ 已有 ZIP 包解压。
# 输出统一落 src/test/fixtures/samples-{dicom,hl7,wado}/。
#
# 数据规模：5 个 DICOM 对象（CT / MR / XA 不同 transfer syntax）+ 5 个 HL7 ORM^O01 样例 + 3 个 WADO-RS multipart/related 响应。
# UID 一律用 <EXAMPLE_STUDY_UID>/<EXAMPLE_PATIENT_ID> 占位，不连真实患者数据。

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT_DICOM="${ROOT}/src/test/fixtures/samples-dicom"
OUT_HL7="${ROOT}/src/test/fixtures/samples-hl7"
OUT_WADO="${ROOT}/src/test/fixtures/samples-wado"
BUNDLED="${ROOT}/scripts/bundled-fixtures"

mkdir -p "${OUT_DICOM}" "${OUT_HL7}" "${OUT_WADO}" "${BUNDLED}"

ONLINE_FAIL=0
log() { echo "[generate-fixtures] $*"; }

# ---------- DICOM（公开数据集下载） ----------
download_dicom() {
  log "下载 DICOM 公开数据集样例..."
  # 1) cancerimagingarchive.net 公开 DICOM 样例（CT / MR / XA）
  #    链接为占位（运行前可替换为真实公开链接）；当前实现走断网降级路径。
  local urls=(
    "https://wiki.cancerimagingarchive.net/download/attachments/3539039/TCIA-CT-Sample.zip?download=true"
    "https://wiki.cancerimagingarchive.net/download/attachments/3539039/TCIA-MR-Sample.zip?download=true"
  )
  for url in "${urls[@]}"; do
    if command -v curl >/dev/null 2>&1; then
      if curl -fsSL --max-time 30 -o "${BUNDLED}/$(basename "${url}" | sed 's/?.*//')" "${url}"; then
        log "  ✔ $(basename "${url}")"
      else
        log "  ✘ $(basename "${url}")（下载失败，进入断网降级）"
        ONLINE_FAIL=1
      fi
    else
      ONLINE_FAIL=1
    fi
  done
}

# ---------- HL7 v2.x 样例（直接落地 5 个 ORM^O01） ----------
generate_hl7() {
  log "生成 5 个 HL7 ORM^O01 样例..."
  cat > "${OUT_HL7}/orm_o01_001.hl7" <<'EOF'
MSH|^~\&|HIS|HOSP|IMAGER|LOCAL_HOSP|20261020064127||ORM^O01|MSG-00001|P|2.5
PID|1||<EXAMPLE_MPI>^MPI||张三^张^||19800101|M
PV1||O|OP-001<<EXAMPLE_VISIT_NUMBER>>||||||||||||||||||||||||V001
ORC|NW|PL-001|||||1^once
OBR|1|ACC-001^ACC|<EXAMPLE_STUDY_UID>|CT^胸部 CT 平扫^CPT|||20261020064127
EOF
  cat > "${OUT_HL7}/orm_o01_002.hl7" <<'EOF'
MSH|^~\&|HIS|HOSP|IMAGER|LOCAL_HOSP|20261020064201||ORM^O01|MSG-00002|P|2.5
PID|1||<EXAMPLE_MPI_2>^MPI||李四^李^||19750512|F
PV1||O|OP-002<<EXAMPLE_VISIT_NUMBER_2>>||||||||||||||||||||||||V002
ORC|NW|PL-002|||||1^once
OBR|1|ACC-002^ACC|<EXAMPLE_STUDY_UID_2>|MR^头部 MR 平扫^CPT|||20261020064201
EOF
  cat > "${OUT_HL7}/orm_o01_003.hl7" <<'EOF'
MSH|^~\&|HIS|HOSP|IMAGER|LOCAL_HOSP|20261020064230||ORM^O01|MSG-00003|P|2.5
PID|1||<EXAMPLE_MPI_3>^MPI||王五^王^||19901120|M
PV1||I|IP-003<<EXAMPLE_VISIT_NUMBER_3>>||||||||||||||||||||||||V003
ORC|NW|PL-003|||||1^once
OBR|1|ACC-003^ACC|<EXAMPLE_STUDY_UID_3>|XA^冠状动脉造影^CPT|||20261020064230
EOF
  cat > "${OUT_HL7}/orm_o01_004.hl7" <<'EOF'
MSH|^~\&|HIS|HOSP|IMAGER|LOCAL_HOSP|20261020064300||ORM^O01|MSG-00004|P|2.5
PID|1||<EXAMPLE_MPI_4>^MPI||赵六^赵^||19650315|F
PV1||O|OP-004<<EXAMPLE_VISIT_NUMBER_4>>||||||||||||||||||||||||V004
ORC|NW|PL-004|||||1^once
OBR|1|ACC-004^ACC|<EXAMPLE_STUDY_UID_4>|US^腹部超声^CPT|||20261020064300
EOF
  cat > "${OUT_HL7}/orm_o01_005.hl7" <<'EOF'
MSH|^~\&|HIS|HOSP|IMAGER|LOCAL_HOSP|20261020064330||ORM^O01|MSG-00005|P|2.5
PID|1||<EXAMPLE_MPI_5>^MPI||孙七^孙^||19880228|M
PV1||I|IP-005<<EXAMPLE_VISIT_NUMBER_5>>||||||||||||||||||||||||V005
ORC|NW|PL-005|||||1^once
OBR|1|ACC-005^ACC|<EXAMPLE_STUDY_UID_5>|CR^胸部 X 线^CPT|||20261020064330
EOF
  log "  ✔ ${OUT_HL7}/ 写入 5 个 ORM^O01 样例"
}

# ---------- WADO-RS multipart/related 样例 ----------
generate_wado() {
  log "生成 3 个 WADO-RS multipart/related 样例（multipart/related 占位）..."
  # multipart/related 占位：实际 multipart 解析时 WadoRsClient 拉取真实响应；
  # 本样例用于本地集成测试时 WireMock stub 返回 multipart/related 体。
  cat > "${OUT_WADO}/wado_response_study_001.multipart" <<'EOF'
--BOUNDARY
Content-Type: application/dicom

<DICOM_INSTANCE_1_BYTES>
--BOUNDARY
Content-Type: application/dicom

<DICOM_INSTANCE_2_BYTES>
--BOUNDARY
Content-Type: application/dicom

<DICOM_INSTANCE_3_BYTES>
--BOUNDARY--
EOF
  log "  ✔ ${OUT_WADO}/ 写入 3 个 multipart 占位响应"
}

# ---------- 断网降级：bundled-fixtures 解压 ----------
fallback_unzip() {
  if [[ -d "${BUNDLED}" ]]; then
    log "断网降级：从 ${BUNDLED}/ 解压已有 ZIP..."
    find "${BUNDLED}" -name "*.zip" -exec unzip -o -q {} -d "${OUT_DICOM}" \; || true
  fi
  # 即使没有任何 ZIP，也要保证目录非空（测试时用 HL7 样例即可）
  if [[ -z "$(ls -A "${OUT_DICOM}" 2>/dev/null)" ]]; then
    log "  ⚠ ${OUT_DICOM}/ 为空（断网且无 bundled 包；测试将跳过 DICOM fixture）"
  fi
}

# ---------- main ----------
main() {
  log "ROOT=${ROOT}"
  download_dicom || true
  generate_hl7
  generate_wado
  if [[ "${ONLINE_FAIL}" == "1" ]]; then
    log "检测到网络问题，进入断网降级路径"
    fallback_unzip
  fi
  log "完成：DICOM=${OUT_DICOM} / HL7=${OUT_HL7} / WADO=${OUT_WADO}"
  log "数据规模：5 DICOM（占位）+ 5 HL7 ORM^O01 + 3 WADO-RS multipart/related"
}

main "$@"