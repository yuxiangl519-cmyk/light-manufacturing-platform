export interface Page<T> { items: T[]; page: number; size: number; total: string }
export type Row = Record<string, any>
export interface CurrentUser { id: string; username: string; displayName: string; roles: string[]; permissions: string[] }
