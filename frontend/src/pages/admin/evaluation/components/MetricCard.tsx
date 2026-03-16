type MetricCardProps = {
  label: string;
  value: number | null;
  threshold: number;
  format: "percent" | "score5";
  reverse?: boolean;
};

export function MetricCard({ label, value, threshold, format, reverse = false }: MetricCardProps) {
  const displayValue =
    value == null
      ? "\u2014"
      : format === "percent"
        ? `${(value * 100).toFixed(1)}%`
        : `${value.toFixed(2)}/5.0`;

  const isPassing = value != null && (reverse ? value <= threshold : value >= threshold);
  const statusColor =
    value == null ? "text-gray-400" : isPassing ? "text-green-600" : "text-red-500";
  const bgColor = value == null ? "bg-gray-50" : isPassing ? "bg-green-50" : "bg-red-50";

  return (
    <div className={`rounded-lg p-4 ${bgColor} border`}>
      <div className="text-sm text-muted-foreground">{label}</div>
      <div className={`text-2xl font-bold mt-1 ${statusColor}`}>{displayValue}</div>
      <div className="text-xs text-muted-foreground mt-1">
        阈值: {format === "percent" ? `${(threshold * 100).toFixed(0)}%` : threshold.toFixed(1)}
      </div>
    </div>
  );
}
