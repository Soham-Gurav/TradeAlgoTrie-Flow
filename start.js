const { spawn, execSync } = require("child_process");
const path = require("path");
const fs = require("fs");

const rootDir = __dirname;
const backendDir = path.join(rootDir, "backend");
const binDir = path.join(backendDir, "bin");
const frontendDir = path.join(rootDir, "frontend");

console.log("====================================================================");
console.log("      STARTING FULL-STACK APPLICATION: NEXT.JS + JAVA API SERVER     ");
console.log("====================================================================");

// 1. Ensure bin directory exists
if (!fs.existsSync(binDir)) {
  fs.mkdirSync(binDir, { recursive: true });
}

// 2. Compile Java Backend
console.log("\n[1/3] Compiling Java backend algorithms...");
try {
  // Collect all java files
  function getJavaFiles(dir) {
    let results = [];
    const list = fs.readdirSync(dir);
    list.forEach(file => {
      const fullPath = path.join(dir, file);
      const stat = fs.statSync(fullPath);
      if (stat && stat.isDirectory()) {
        results = results.concat(getJavaFiles(fullPath));
      } else if (file.endsWith(".java")) {
        results.push(fullPath);
      }
    });
    return results;
  }

  const javaFiles = getJavaFiles(path.join(backendDir, "src"));
  const compileCmd = `javac -d "${binDir}" ${javaFiles.map(f => `"${f}"`).join(" ")}`;
  execSync(compileCmd, { stdio: "inherit" });
  console.log("  >> Java compilation successful!");
} catch (e) {
  console.error("  >> [ERROR] Java compilation failed:", e.message);
  process.exit(1);
}

// 3. Launch Java Backend Server (Port 8085)
console.log("\n[2/3] Launching Java REST API Server on http://localhost:8085...");
const backend = spawn("java", ["-cp", binDir, "algo.api.AlgoApiServer"], {
  cwd: rootDir,
  stdio: "inherit",
});

// 4. Launch Next.js Frontend (Port 3000)
console.log("\n[3/3] Launching Next.js Frontend on http://localhost:3000...");
const frontend = spawn("npm", ["run", "dev"], {
  cwd: frontendDir,
  shell: true,
  stdio: "inherit",
});

// Handle graceful shutdown
function cleanup() {
  console.log("\nShutting down backend and frontend...");
  backend.kill();
  frontend.kill();
  process.exit(0);
}

process.on("SIGINT", cleanup);
process.on("SIGTERM", cleanup);

