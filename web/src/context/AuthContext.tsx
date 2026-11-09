import React, {createContext, useContext, useEffect, useMemo, useState} from 'react';
import {api} from '../lib/api';
type User = {userId:string; username:string; role:string; fullName:string; permissions:string[]; mustChangePassword?:boolean};
type AuthContextValue = {user:User|null; loading:boolean; login:(username:string,password:string)=>Promise<{requires2fa:boolean;pendingToken?:string}>; acceptToken:(token:string,user:User)=>void; logout:()=>Promise<void>; can:(permission:string)=>boolean};
const AuthContext = createContext<AuthContextValue|null>(null);
export function AuthProvider({children}:{children:React.ReactNode}) {
 const [user,setUser]=useState<User|null>(()=>{try{return JSON.parse(sessionStorage.getItem('pos_user')||'null')}catch{return null}});
 const [loading,setLoading]=useState(false);
 useEffect(()=>{const onUnauthorized=()=>setUser(null);window.addEventListener('pos:unauthorized',onUnauthorized);return()=>window.removeEventListener('pos:unauthorized',onUnauthorized)},[]);
 const acceptToken=(token:string,u:User)=>{sessionStorage.setItem('pos_token',token);sessionStorage.setItem('pos_user',JSON.stringify(u));setUser(u)};
 const login=async(username:string,password:string)=>{setLoading(true);try{const r=await api<any>('/api/auth/login',{method:'POST',body:JSON.stringify({username,password})});const d=r.data;if(d.requires2fa)return {requires2fa:true,pendingToken:d.pendingToken};if(!d.token)throw new Error('The API did not return a session token');acceptToken(d.token,{userId:d.userId,username:d.username,role:d.role,fullName:d.fullName,permissions:d.permissions||[],mustChangePassword:d.mustChangePassword});return {requires2fa:false}}finally{setLoading(false)}};
 const logout=async()=>{try{if(sessionStorage.getItem('pos_token'))await api('/api/auth/logout',{method:'POST'})}catch{}sessionStorage.removeItem('pos_token');sessionStorage.removeItem('pos_user');setUser(null)};
 const value=useMemo(()=>({user,loading,login,acceptToken,logout,can:(p:string)=>!!user?.permissions?.includes(p)||user?.role?.toLowerCase()==='master_admin'}),[user,loading]);
 return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}
export function useAuth(){const v=useContext(AuthContext);if(!v)throw new Error('useAuth must be inside AuthProvider');return v}
