export async function mount(ctx) {
  await ctx.loadDashboard(ctx.force);
}
