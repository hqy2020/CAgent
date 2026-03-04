import { useEffect, useRef, useState } from "react";

const CHARS_PER_FRAME = 3;
const CATCH_UP_THRESHOLD = 40;
const CATCH_UP_SPEED = 8;

export function useAnimatedText(rawText: string, isStreaming: boolean): string {
  const [displayText, setDisplayText] = useState(rawText);
  const displayedLengthRef = useRef(rawText.length);
  const rafRef = useRef<number>(0);

  useEffect(() => {
    if (!isStreaming) {
      cancelAnimationFrame(rafRef.current);
      displayedLengthRef.current = rawText.length;
      setDisplayText(rawText);
      return;
    }

    const animate = () => {
      const target = rawText.length;
      const current = displayedLengthRef.current;
      if (current >= target) {
        rafRef.current = requestAnimationFrame(animate);
        return;
      }
      const gap = target - current;
      const step = gap > CATCH_UP_THRESHOLD ? CATCH_UP_SPEED : CHARS_PER_FRAME;
      const next = Math.min(current + step, target);
      displayedLengthRef.current = next;
      setDisplayText(rawText.substring(0, next));
      rafRef.current = requestAnimationFrame(animate);
    };

    rafRef.current = requestAnimationFrame(animate);
    return () => cancelAnimationFrame(rafRef.current);
  }, [rawText, isStreaming]);

  return displayText;
}
