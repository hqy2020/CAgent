import { BarChart, Bar, XAxis, YAxis, Tooltip, ResponsiveContainer } from "recharts";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";

type ScoreDistributionChartProps = {
  title: string;
  data: Record<number, number> | undefined;
  color: string;
};

export function ScoreDistributionChart({ title, data, color }: ScoreDistributionChartProps) {
  const chartData = [1, 2, 3, 4, 5].map((score) => ({
    score: `${score}分`,
    count: data?.[score] || 0,
  }));

  return (
    <Card>
      <CardHeader className="pb-2">
        <CardTitle className="text-sm">{title}</CardTitle>
      </CardHeader>
      <CardContent>
        <ResponsiveContainer width="100%" height={200}>
          <BarChart data={chartData}>
            <XAxis dataKey="score" />
            <YAxis allowDecimals={false} />
            <Tooltip />
            <Bar dataKey="count" fill={color} radius={[4, 4, 0, 0]} />
          </BarChart>
        </ResponsiveContainer>
      </CardContent>
    </Card>
  );
}
