"""
veilframe.image.runtime.registry — Detector & Sanitizer provider registries.

Provides simple lookup registries that map DetectorClass → provider instances
and sanitizer layer → sanitizer instances.  The registry is populated at
pipeline startup and its identity (hash of registered provider fingerprints)
is included in the CapabilityReport.
"""

from __future__ import annotations

import hashlib
import json
from typing import Dict, Generic, List, Optional, Type, TypeVar

from ..models.status import DetectorClass

T = TypeVar("T")


class ProviderRegistry(Generic[T]):
    """Generic registry mapping string keys to provider instances."""

    def __init__(self) -> None:
        self._providers: Dict[str, T] = {}

    def register(self, key: str, provider: T) -> None:
        self._providers[key] = provider

    def get(self, key: str) -> Optional[T]:
        return self._providers.get(key)

    def all(self) -> List[T]:
        return list(self._providers.values())

    def keys(self) -> List[str]:
        return list(self._providers.keys())

    def __len__(self) -> int:
        return len(self._providers)


class DetectorRegistry(ProviderRegistry):
    """Registry mapping DetectorClass → DetectionProvider."""

    def register_detector(self, cls: DetectorClass, provider) -> None:
        self.register(cls.value, provider)

    def get_detector(self, cls: DetectorClass):
        return self.get(cls.value)

    def registered_classes(self) -> List[DetectorClass]:
        return [DetectorClass(k) for k in self.keys() if k in DetectorClass._value2member_map_]


class SanitizerRegistry(ProviderRegistry):
    """Registry mapping sanitizer layer names (A/B/C) → sanitizer instances."""

    def register_layer(self, layer: str, sanitizer) -> None:
        if layer not in ("A", "B", "C"):
            raise ValueError(f"Layer must be A, B, or C; got {layer!r}")
        self.register(layer, sanitizer)

    def get_layer(self, layer: str):
        return self.get(layer)
