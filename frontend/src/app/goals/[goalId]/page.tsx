import CoreApp from "@/components/CoreApp";
export default async function Goal({
  params,
}: {
  params: Promise<{ goalId: string }>;
}) {
  const { goalId } = await params;
  return <CoreApp key={goalId} initialGoal={goalId} />;
}
