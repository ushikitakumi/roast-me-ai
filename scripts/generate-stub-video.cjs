const {spawnSync}=require('node:child_process');
const path=require('node:path');
const binary=require('../frontend/node_modules/ffmpeg-static');
const output=path.join(__dirname,'../backend/src/main/resources/fixtures/stub.mp4');
const args=['-hide_banner','-loglevel','error','-y','-f','lavfi','-i','color=c=0x292e27:s=640x360:d=2','-vf',"drawtext=text='STUB VIDEO - NO AI CALLS':fontcolor=white:fontsize=26:x=(w-tw)/2:y=(h-th)/2",'-c:v','libx264','-pix_fmt','yuv420p','-movflags','+faststart',output];
const result=spawnSync(binary,args,{stdio:'inherit'});process.exit(result.status??1);
