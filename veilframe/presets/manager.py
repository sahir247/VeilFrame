"""
Preset manager for loading and applying configuration profiles.
"""
import json
from pathlib import Path
from typing import Dict, Any, List, Optional
from ..models.settings import (
    ProcessingSettings,
    CropSettings,
    ResizeSettings,
    FpsSettings,
    TrimSettings,
    NoiseSettings,
    ColorSettings,
    AudioPrivacySettings,
    QuantizationSettings,
    CodecSettings,
    QualitySettings,
    PrivacySettings,
    VisualBudgetPolicy,
)

PRESETS_FILE = Path(__file__).parent / "profiles.json"


class PresetManager:
    def __init__(self, custom_path: Optional[Path] = None):
        self.presets_path = custom_path or PRESETS_FILE
        self._profiles: Dict[str, Any] = self._load()

    def _load(self) -> Dict[str, Any]:
        if self.presets_path.exists():
            try:
                with open(self.presets_path, "r", encoding="utf-8") as f:
                    return json.load(f)
            except Exception:
                pass
        return {}

    def get_preset_names(self) -> List[str]:
        return list(self._profiles.keys())

    def get_preset_description(self, name: str) -> str:
        return self._profiles.get(name, {}).get("description", "")

    def get_preset(self, name: str) -> Optional[Dict[str, Any]]:
        if name in self._profiles:
            return self._profiles[name]
        for k, v in self._profiles.items():
            if name.lower() in k.lower():
                return v
        if "10%" in name or name.strip() == "10":
            return self._profiles.get("10% Bounded Forensic Disruption")
        if "5%" in name or name.strip() == "5":
            return self._profiles.get("5% Bounded Forensic Disruption")
        return None

    def to_processing_settings(self, data: Dict[str, Any], current_settings: Optional[ProcessingSettings] = None) -> ProcessingSettings:
        # Find preset name matching data if any
        matched_name = "Custom"
        for k, v in self._profiles.items():
            if v == data:
                matched_name = k
                break
        return self._apply_data(matched_name, data, current_settings)

    def apply_preset(self, name: str, current_settings: Optional[ProcessingSettings] = None) -> ProcessingSettings:
        target_name = name
        if name not in self._profiles:
            if "10%" in name or name.strip() == "10":
                target_name = "10% Bounded Forensic Disruption"
            elif "5%" in name or name.strip() == "5":
                target_name = "5% Bounded Forensic Disruption"
            else:
                for k in self._profiles:
                    if name.lower() in k.lower():
                        target_name = k
                        break

        data = self._profiles.get(target_name, {})
        return self._apply_data(target_name, data, current_settings)

    def _apply_data(self, name: str, data: Dict[str, Any], current_settings: Optional[ProcessingSettings] = None) -> ProcessingSettings:
        settings = current_settings or ProcessingSettings()
        settings.preset_name = name

        if "crop" in data:
            c = data["crop"]
            settings.crop = CropSettings(
                enabled=c.get("enabled", False),
                mode=c.get("mode", "auto"),
                asymmetric=c.get("asymmetric", True),
                left=c.get("left", 0),
                right=c.get("right", 0),
                top=c.get("top", 0),
                bottom=c.get("bottom", 0),
                x=c.get("x", 0),
                y=c.get("y", 0),
                width=c.get("width", 0),
                height=c.get("height", 0),
            )

        if "resize" in data:
            r = data["resize"]
            settings.resize = ResizeSettings(
                enabled=r.get("enabled", False),
                mode=r.get("mode", "auto"),
                width=r.get("width", 1920),
                height=r.get("height", 1080),
                maintain_aspect=r.get("maintain_aspect", True),
            )

        if "fps" in data:
            f = data["fps"]
            settings.fps = FpsSettings(
                enabled=f.get("enabled", False),
                mode=f.get("mode", "auto"),
                fps=float(f.get("fps", 30.0)),
            )

        if "trim" in data:
            t = data["trim"]
            settings.trim = TrimSettings(
                enabled=t.get("enabled", False),
                mode=t.get("mode", "auto"),
                start=float(t.get("start", 0.0)),
                end=t.get("end"),
                duration=t.get("duration"),
            )

        if "noise" in data:
            n = data["noise"]
            settings.noise = NoiseSettings(
                enabled=n.get("enabled", False),
                mode=n.get("mode", "auto"),
                strength=int(n.get("strength", 1)),
                prnu_mode=n.get("prnu_mode", "gaussian"),
                cfa_pattern=n.get("cfa_pattern", "RGGB"),
                cfa_gamma=float(n.get("cfa_gamma", 0.6)),
                hash_perturbation_enabled=n.get("hash_perturbation_enabled", False),
                hash_perturbation_budget=float(n.get("hash_perturbation_budget", 0.02)),
            )

        if "color" in data:
            cl = data["color"]
            settings.color = ColorSettings(
                enabled=cl.get("enabled", False),
                mode=cl.get("mode", "auto"),
                contrast=float(cl.get("contrast", 1.015)),
                brightness=float(cl.get("brightness", 0.005)),
                gamma=float(cl.get("gamma", 0.985)),
                saturation=float(cl.get("saturation", 1.02)),
            )

        if "audio_privacy" in data:
            ap = data["audio_privacy"]
            settings.audio_privacy = AudioPrivacySettings(
                enabled=ap.get("enabled", False),
                mode=ap.get("mode", "auto"),
                enf_notch=ap.get("enf_notch", True),
                micro_pitch=ap.get("micro_pitch", True),
                pitch_ratio=float(ap.get("pitch_ratio", 0.99)),
                noise_floor_dither=ap.get("noise_floor_dither", False),
            )

        if "quantization" in data:
            qz = data["quantization"]
            settings.quantization = QuantizationSettings(
                forced_gop=qz.get("forced_gop", True),
                gop_size=int(qz.get("gop_size", 48)),
                scene_change_threshold=int(qz.get("scene_change_threshold", 0)),
                normalize_timestamps=qz.get("normalize_timestamps", True),
                epoch_zero=qz.get("epoch_zero", True),
                bitexact=qz.get("bitexact", True),
            )

        if "codec" in data:
            cd = data["codec"]
            settings.codec = CodecSettings(
                mode=cd.get("mode", "auto"),
                codec=cd.get("codec", "h264"),
            )

        if "quality" in data:
            q = data["quality"]
            settings.quality = QualitySettings(
                mode=q.get("mode", "auto"),
                crf=int(q.get("crf", 18)),
                bitrate_kbps=int(q.get("bitrate_kbps", 12000)),
            )

        if "privacy" in data:
            p = data["privacy"]
            settings.privacy = PrivacySettings(
                remove_metadata=p.get("remove_metadata", True),
                remove_comments=p.get("remove_comments", True),
                remove_chapters=p.get("remove_chapters", True),
                remove_attachments=p.get("remove_attachments", True),
                scrub_after_encoding=p.get("scrub_after_encoding", True),
                verify_output=p.get("verify_output", True),
            )

        if "quality_gate" in data:
            qg = data["quality_gate"]
            settings.quality_gate = VisualBudgetPolicy(
                enabled=qg.get("enabled", True),
                enforce_strict=qg.get("enforce_strict", False),
                policy_budget=float(qg.get("policy_budget", 0.05)),
                spatial_ceiling_pct=float(qg.get("spatial_ceiling_pct", 2.0)),
                temporal_ceiling_pct=float(qg.get("temporal_ceiling_pct", 1.0)),
                luma_ceiling_pct=float(qg.get("luma_ceiling_pct", 1.0)),
                chroma_ceiling_pct=float(qg.get("chroma_ceiling_pct", 1.0)),
                frequency_ceiling_pct=float(qg.get("frequency_ceiling_pct", 1.0)),
                aggregate_ceiling_pct=float(qg.get("aggregate_ceiling_pct", 5.0)),
                ssim_mean_min=float(qg.get("ssim_mean_min", 0.95)),
                ssim_p5_min=float(qg.get("ssim_p5_min", 0.90)),
                ssim_worst_min=float(qg.get("ssim_worst_min", 0.85)),
                psnr_mean_min_db=float(qg.get("psnr_mean_min_db", 30.0)),
                psnr_worst_min_db=float(qg.get("psnr_worst_min_db", 25.0)),
                max_eval_frames=int(qg.get("max_eval_frames", 800)),
            )

        return settings
