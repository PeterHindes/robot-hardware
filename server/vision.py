"""
Easy-to-edit Vision Processing Pipeline for Robot Hardware Host Hub.

Receives every decoded WebRTC RGB frame (np.ndarray, HxWx3 uint8) from the Pixel 4a,
runs OpenCV processing, optionally annotates the frame for the dashboard preview,
and returns an Arduino serial command (e.g. "LED ON" / "LED OFF") when state changes.
"""

from typing import Optional
import cv2
import numpy as np


class VisionProcessor:
    def __init__(self) -> None:
        cascade_path = cv2.data.haarcascades + "haarcascade_frontalface_default.xml"
        self.face_cascade = cv2.CascadeClassifier(cascade_path)
        self.face_visible: Optional[bool] = None
        self._miss_streak: int = 0
        self.last_face_count: int = 0

    def process(self, img_rgb: np.ndarray) -> tuple[np.ndarray, Optional[str]]:
        """
        Process a single RGB frame and optionally return a serial command to send.
        Returns: (annotated_rgb, serial_cmd_or_none)
        """
        out = img_rgb.copy()
        gray = cv2.cvtColor(img_rgb, cv2.COLOR_RGB2GRAY)

        # Downscale for fast detection on every frame
        scale = 0.5
        small = cv2.resize(gray, (0, 0), fx=scale, fy=scale)
        faces = self.face_cascade.detectMultiScale(
            small,
            scaleFactor=1.15,
            minNeighbors=4,
            minSize=(24, 24),
        )

        self.last_face_count = len(faces)
        detected = len(faces) > 0

        for (x, y, w, h) in faces:
            x1, y1 = int(x / scale), int(y / scale)
            x2, y2 = int((x + w) / scale), int((y + h) / scale)
            cv2.rectangle(out, (x1, y1), (x2, y2), (0, 230, 118), 2)
            cv2.putText(
                out,
                "FACE",
                (x1, max(20, y1 - 8)),
                cv2.FONT_HERSHEY_SIMPLEX,
                0.6,
                (0, 230, 118),
                2,
            )

        cmd: Optional[str] = None
        if detected:
            self._miss_streak = 0
            if self.face_visible is not True:
                self.face_visible = True
                cmd = "LED ON"
        else:
            self._miss_streak += 1
            # Require 4 consecutive misses before turning LED OFF to avoid flicker
            if self._miss_streak >= 4 and self.face_visible is not False:
                self.face_visible = False
                cmd = "LED OFF"

        status_txt = f"Faces: {self.last_face_count} | LED: {'ON' if self.face_visible else 'OFF'}"
        cv2.putText(
            out,
            status_txt,
            (12, 26),
            cv2.FONT_HERSHEY_SIMPLEX,
            0.7,
            (0, 230, 118) if self.face_visible else (255, 167, 38),
            2,
        )
        return out, cmd
